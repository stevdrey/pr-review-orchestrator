package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Conflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Unavailable;
import io.github.stevdrey.prrevieworchestrator.ports.SandboxInlineFixStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcSandboxInlineFixStore implements SandboxInlineFixStore {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcSandboxInlineFixStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
    }

    @Override
    public boolean record(PullRequestId pr, SandboxInlineFix proof, Runnable authorize) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    PostgresLocks.lockPr(jdbc, pr);
                    return recordLocked(pr, proof);
                } catch (RuntimeException failure) {
                    throw sanitized(failure);
                }
            }));
        } catch (RuntimeException failure) {
            throw sanitized(failure);
        }
    }

    private boolean recordLocked(PullRequestId pr, SandboxInlineFix proof) {
        var rows = jdbc.query("""
                SELECT f.id, f.status, f.evidence_sha, f.disposition_actor, f.disposition_reason
                FROM findings f
                JOIN review_evidence_snapshots o ON o.id = f.evidence_snapshot_id
                JOIN review_evidence_sources s ON s.snapshot_id = o.id AND s.source_id = f.source_id
                JOIN pr_review_states p ON (p.installation_id, p.repository_id, p.pr_number) =
                    (f.installation_id, f.repository_id, f.pr_number)
                WHERE f.installation_id = ? AND f.repository_id = ? AND f.pr_number = ?
                    AND f.stable_finding_id = ? AND f.evidence_snapshot_id = ?
                    AND f.source_kind = 'REVIEW_COMMENT' AND s.reply_to_id IS NULL
                    AND coalesce(s.original_sha, s.reviewed_sha) = ? AND s.path = ?
                    AND f.reviewed_sha = coalesce(s.original_sha, s.reviewed_sha)
                    AND f.reviewed_sha <> p.head_sha AND p.head_sha = ? AND p.version = ?
                    AND NOT EXISTS (
                        SELECT 1 FROM findings n
                        JOIN review_evidence_snapshots newer ON newer.id = n.evidence_snapshot_id
                        WHERE n.stable_finding_id = f.stable_finding_id AND n.id <> f.id
                            AND newer.captured_at >= o.captured_at)
                """, (rs, row) -> new Target(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("evidence_sha"), rs.getString("disposition_actor"),
                        rs.getString("disposition_reason")), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), proof.findingId(), proof.snapshotId(), proof.originalCommit(), proof.path(),
                proof.correctiveCommit(), proof.expectedVersion());
        if (rows.size() != 1) throw new Conflict();
        var target = rows.getFirst();
        String reason = "Compared " + proof.originalCommit() + " to " + proof.correctiveCommit()
                + ", file blobs " + proof.originalBlob() + " to " + proof.correctiveBlob() + ". " + proof.reason();
        if ("VERIFIED_FIXED".equals(target.status()) && proof.correctiveCommit().equals(target.commit())
                && proof.verifier().equals(target.actor()) && reason.equals(target.reason())) return false;
        if (!"OPEN".equals(target.status()) && !"ACKNOWLEDGED".equals(target.status())) throw new Conflict();
        if (jdbc.update("""
                UPDATE findings SET status = 'VERIFIED_FIXED', evidence_sha = ?, verified_at = clock_timestamp(),
                    disposition_actor = ?, disposition_reason = ?
                WHERE id = ?
                """, proof.correctiveCommit(), proof.verifier(), reason, target.id()) != 1) throw new Conflict();
        return true;
    }

    private record Target(UUID id, String status, String commit, String actor, String reason) {}

    private RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof Conflict) return new Conflict();
        if (failure instanceof Unavailable known) return new Unavailable(known.retryable());
        return new Unavailable(!(failure instanceof IllegalArgumentException || failure instanceof DataIntegrityViolationException));
    }
}
