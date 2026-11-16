package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReviewEvidenceStore implements ReviewEvidenceStore {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReviewEvidenceStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        PostgresDeadlines.requireTransactionTimeout(jdbc, "review evidence");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(3);
    }

    @Override
    public boolean ingest(Snapshot snapshot, Runnable authorize) {
        String fingerprint = fingerprint(snapshot);
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    return persist(snapshot, fingerprint);
                } catch (RuntimeException failure) { throw sanitized(failure); }
            }));
        } catch (RuntimeException failure) { throw sanitized(failure); }
    }

    private boolean persist(Snapshot snapshot, String fingerprint) {
        var pr = snapshot.pr();
        // Cycle locking serializes same-round updates and makes the current projection deterministic.
        var cycles = jdbc.query("""
                SELECT id FROM review_cycles WHERE id = ? AND installation_id = ? AND repository_id = ?
                    AND pr_number = ? AND head_sha = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), snapshot.cycleId(), pr.scope().installationId(),
                pr.scope().repositoryId(), pr.number(), snapshot.headSha());
        if (cycles.isEmpty()) throw new Conflict();
        var existing = jdbc.query("SELECT fingerprint FROM review_evidence_snapshots WHERE id = ?",
                (rs, row) -> rs.getString(1), snapshot.id());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(fingerprint)) throw new Conflict();
            return false;
        }
        // Equal observation times cannot establish ordering; do not silently choose a winner.
        if (!jdbc.query("SELECT id FROM review_evidence_snapshots WHERE review_cycle_id = ? AND captured_at = ?",
                (rs, row) -> rs.getObject(1, UUID.class), snapshot.cycleId(), Timestamp.from(snapshot.capturedAt())).isEmpty()) {
            throw new Conflict();
        }
        jdbc.update("""
                INSERT INTO review_evidence_snapshots (id, review_cycle_id, installation_id, repository_id,
                    pr_number, head_sha, provider, captured_at, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.id(), snapshot.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), snapshot.headSha(), snapshot.provider(), Timestamp.from(snapshot.capturedAt()), fingerprint);
        for (Source source : snapshot.sources()) {
            jdbc.update("""
                    INSERT INTO review_evidence_sources (snapshot_id, source_id, source_kind, review_id, thread_id,
                        reply_to_id, author_login, reviewed_sha, original_sha, body_sha256, source_updated_at,
                        review_state, thread_resolved, thread_outdated, path, line, original_line, severity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.id(), source.id(), source.kind().name(), source.reviewId(), source.threadId(),
                    source.replyToId(), source.author(), source.reviewedSha(), source.originalSha(), source.bodySha256(),
                    source.updatedAt() == null ? null : Timestamp.from(source.updatedAt()),
                    source.reviewState(), source.resolved(), source.outdated(),
                    source.path(), source.line(), source.originalLine(), source.severity().name());
            if (source.candidate()) projectFinding(snapshot, source);
        }
        return true;
    }

    private void projectFinding(Snapshot snapshot, Source source) {
        var pr = snapshot.pr();
        UUID identity = jdbc.queryForObject("""
                INSERT INTO finding_identities (id, installation_id, repository_id, pr_number, provider, source_kind, source_id)
                VALUES (?, ?, ?, ?, ?, 'REVIEW_COMMENT', ?)
                ON CONFLICT (installation_id, repository_id, pr_number, provider, source_kind, source_id)
                DO UPDATE SET source_id = excluded.source_id RETURNING id
                """, UUID.class, UUID.randomUUID(), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), snapshot.provider(), source.id());
        // Each round has its own occurrence, but all observations of this source share one stable identity.
        // A fresh observation conservatively reopens a prior disposition; only a separate verifier may fix it.
        jdbc.update("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha,
                    source_provider, source_kind, source_id, thread_id, body_ref, severity, thread_resolved,
                    stable_finding_id, evidence_snapshot_id, pr_number)
                VALUES (?, ?, ?, ?, ?, ?, 'REVIEW_COMMENT', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (review_cycle_id, source_provider, source_kind, source_id, source_ordinal)
                DO UPDATE SET thread_id = excluded.thread_id, severity = excluded.severity,
                    thread_resolved = excluded.thread_resolved, stable_finding_id = excluded.stable_finding_id,
                    evidence_snapshot_id = excluded.evidence_snapshot_id, pr_number = excluded.pr_number,
                    status = 'OPEN', evidence_sha = NULL, verified_at = NULL,
                    disposition_actor = NULL, disposition_reason = NULL
                WHERE findings.evidence_snapshot_id IS NULL OR
                    (SELECT captured_at FROM review_evidence_snapshots WHERE id = findings.evidence_snapshot_id) < ?
                """, UUID.randomUUID(), snapshot.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(),
                snapshot.headSha(), snapshot.provider(), source.id(), source.threadId(), "github-node:" + source.id(),
                source.severity().name(), source.resolved(), identity, snapshot.id(), pr.number(),
                Timestamp.from(snapshot.capturedAt()));
    }

    private static RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof Conflict) return new Conflict();
        return new Unavailable();
    }

    /** Length-prefixed fields avoid delimiter collisions in untrusted paths, logins and provider IDs. */
    private static String fingerprint(Snapshot snapshot) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            fields(out, snapshot.id(), snapshot.cycleId(), snapshot.pr().scope().installationId(),
                    snapshot.pr().scope().repositoryId(), snapshot.pr().number(), snapshot.provider(),
                    snapshot.headSha(), snapshot.capturedAt(), snapshot.sources().size());
            for (Source s : snapshot.sources()) {
                fields(out, s.id(), s.kind(), s.reviewId(), s.threadId(), s.replyToId(), s.author(),
                        s.reviewedSha(), s.originalSha(), s.bodySha256(), s.updatedAt(), s.reviewState(),
                        s.resolved(), s.outdated(), s.path(), s.line(), s.originalLine(), s.severity());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException ignored) { throw new Unavailable(); }
    }

    private static void fields(DataOutputStream out, Object... values) throws IOException {
        for (Object value : values) {
            if (value == null) { out.writeInt(-1); continue; }
            byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length); out.write(encoded);
        }
    }
}
