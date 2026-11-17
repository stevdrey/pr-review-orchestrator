package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReviewEvidenceStore implements ReviewEvidenceStore {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    /** The occurrence's linked source revision equals the incoming retained source revision. */
    private static final String UNCHANGED = """
            EXISTS (SELECT 1 FROM review_evidence_sources p JOIN review_evidence_sources n
                    ON n.snapshot_id = excluded.evidence_snapshot_id AND n.source_id = p.source_id
                WHERE p.snapshot_id = findings.evidence_snapshot_id AND p.source_id = findings.source_id
                    AND p.body_sha256 = n.body_sha256
                    AND p.source_updated_at IS NOT DISTINCT FROM n.source_updated_at
                    AND p.feedback = n.feedback)""";
    private static final String UPDATE_FINDING = """
                ON CONFLICT (review_cycle_id, source_provider, source_kind, source_id, source_ordinal)
                DO UPDATE SET thread_id = excluded.thread_id, severity = excluded.severity,
                    thread_resolved = excluded.thread_resolved, stable_finding_id = excluded.stable_finding_id,
                    pr_number = excluded.pr_number,
                    status = CASE WHEN %1$s THEN findings.status ELSE 'OPEN' END,
                    evidence_sha = CASE WHEN %1$s THEN findings.evidence_sha END,
                    verified_at = CASE WHEN %1$s THEN findings.verified_at END,
                    disposition_actor = CASE WHEN %1$s THEN findings.disposition_actor END,
                    disposition_reason = CASE WHEN %1$s THEN findings.disposition_reason END,
                    evidence_snapshot_id = excluded.evidence_snapshot_id
                WHERE findings.evidence_snapshot_id IS NULL OR
                    (SELECT captured_at FROM review_evidence_snapshots WHERE id = findings.evidence_snapshot_id) <
                    (SELECT captured_at FROM review_evidence_snapshots WHERE id = excluded.evidence_snapshot_id)
                """.formatted(UNCHANGED);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReviewEvidenceStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        PostgresDeadlines.requireTransactionTimeout(jdbc, "review evidence");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
    }

    @Override
    public boolean ingest(Snapshot snapshot, Runnable authorize) {
        String fingerprint = fingerprint(snapshot, true);
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
        JdbcGeneralFeedbackStore.lockPr(jdbc, pr);
        // Cycle locking serializes same-round updates and makes the current projection deterministic.
        var cycles = jdbc.query("""
                SELECT id FROM review_cycles WHERE id = ? AND installation_id = ? AND repository_id = ?
                    AND pr_number = ? AND head_sha = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), snapshot.cycleId(), pr.scope().installationId(),
                pr.scope().repositoryId(), pr.number(), snapshot.headSha());
        if (cycles.isEmpty()) throw new Conflict();
        var existing = jdbc.queryForList("SELECT fingerprint, fingerprint_version FROM review_evidence_snapshots WHERE id = ?", snapshot.id());
        if (!existing.isEmpty()) {
            var previous = existing.getFirst();
            String expected = ((Number) previous.get("fingerprint_version")).intValue() == 1
                    ? fingerprint(snapshot, false) : fingerprint;
            if (!previous.get("fingerprint").equals(expected)) throw new Conflict();
            return false;
        }
        // Equal observation times cannot establish ordering; do not silently choose a winner.
        if (!jdbc.query("""
                SELECT id FROM review_evidence_snapshots WHERE installation_id = ? AND repository_id = ?
                AND pr_number = ? AND provider = ? AND captured_at = ?
                """,
                (rs, row) -> rs.getObject(1, UUID.class), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), snapshot.provider(),
                Timestamp.from(snapshot.capturedAt())).isEmpty()) {
            throw new Conflict();
        }
        jdbc.update("""
                INSERT INTO review_evidence_snapshots (id, review_cycle_id, installation_id, repository_id,
                    pr_number, head_sha, provider, captured_at, fingerprint, fingerprint_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 2)
                """, snapshot.id(), snapshot.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), snapshot.headSha(), snapshot.provider(), Timestamp.from(snapshot.capturedAt()), fingerprint);
        // Batched: a fixed number of round trips regardless of source count keeps large PRs within the deadline.
        jdbc.batchUpdate("""
                INSERT INTO review_evidence_sources (snapshot_id, source_id, source_kind, review_id, thread_id,
                    reply_to_id, author_login, reviewed_sha, original_sha, body_sha256, source_updated_at,
                    review_state, thread_resolved, thread_outdated, path, line, original_line, severity, feedback)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.sources().stream().map(source -> new Object[] {snapshot.id(), source.id(),
                source.kind().name(), source.reviewId(), source.threadId(), source.replyToId(), source.author(),
                source.reviewedSha(), source.originalSha(), source.bodySha256(),
                source.updatedAt() == null ? null : Timestamp.from(source.updatedAt()),
                source.reviewState(), source.resolved(), source.outdated(),
                source.path(), source.line(), source.originalLine(), source.severity().name(), source.feedback().name()}).toList());
        // Once tracked, blanking a body is an edit requiring a new human decision, not a fix.
        var tracked = new HashSet<String>();
        jdbc.query("""
                SELECT source_id FROM finding_identities WHERE installation_id = ? AND repository_id = ?
                    AND pr_number = ? AND provider = ? AND source_kind IN ('REVIEW', 'ISSUE_COMMENT')
                """, rs -> { tracked.add(rs.getString(1)); }, pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), snapshot.provider());
        var candidates = snapshot.sources().stream()
                .filter(s -> s.candidate() || s.general() && tracked.contains(s.id())).toList();
        if (!candidates.isEmpty()) projectFindings(snapshot, candidates);
        reconcileGeneralHistory(snapshot);
        JdbcGeneralFeedbackStore.invalidateClean(jdbc, pr);
        return true;
    }

    private void projectFindings(Snapshot snapshot, List<Source> candidates) {
        var pr = snapshot.pr();
        // DO NOTHING avoids rewriting (and locking) unchanged identity rows on every observation.
        jdbc.batchUpdate("""
                INSERT INTO finding_identities (id, installation_id, repository_id, pr_number, provider, source_kind, source_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (installation_id, repository_id, pr_number, provider, source_kind, source_id) DO NOTHING
                """, candidates.stream().map(source -> new Object[] {UUID.randomUUID(), pr.scope().installationId(),
                pr.scope().repositoryId(), pr.number(), snapshot.provider(), source.kind().name(), source.id()}).toList());
        Array ids = jdbc.execute((ConnectionCallback<Array>) connection ->
                connection.createArrayOf("text", candidates.stream().map(Source::id).toArray()));
        var identities = new HashMap<String, UUID>();
        jdbc.query("""
                SELECT source_id, id FROM finding_identities WHERE installation_id = ? AND repository_id = ?
                    AND pr_number = ? AND provider = ? AND source_id = ANY(?)
                """, rs -> { identities.put(rs.getString(1), rs.getObject(2, UUID.class)); },
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), snapshot.provider(), ids);
        // Each round has its own occurrence, but all observations of this source share one stable identity.
        // A changed source revision (body hash or update time) conservatively reopens a prior disposition;
        // an unchanged re-read keeps it. Only a separate verifier may mark a finding fixed.
        jdbc.batchUpdate("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha,
                    source_provider, source_kind, source_id, thread_id, body_ref, severity, thread_resolved,
                    stable_finding_id, evidence_snapshot_id, pr_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + UPDATE_FINDING, candidates.stream().map(source -> new Object[] {UUID.randomUUID(),
                snapshot.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(), snapshot.headSha(),
                snapshot.provider(), source.kind().name(), source.id(), source.threadId(), "github-node:" + source.id(),
                source.severity().name(), source.resolved(), identities.get(source.id()), snapshot.id(), pr.number()}).toList());
    }

    /** A delayed first candidate must also track newer blank revisions already retained in other reads/rounds. */
    private void reconcileGeneralHistory(Snapshot snapshot) {
        var pr = snapshot.pr();
        // The PR lock covers every affected round. One set-based write avoids a query per retained observation.
        jdbc.update("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha,
                    source_provider, source_kind, source_id, thread_id, body_ref, severity, thread_resolved,
                    stable_finding_id, evidence_snapshot_id, pr_number)
                SELECT gen_random_uuid(), latest.review_cycle_id, latest.installation_id, latest.repository_id,
                    latest.head_sha, latest.provider, latest.source_kind, latest.source_id, NULL,
                    'github-node:' || latest.source_id, latest.severity, NULL, latest.stable_finding_id,
                    latest.snapshot_id, latest.pr_number
                FROM (
                    SELECT DISTINCT ON (o.review_cycle_id, s.source_kind, s.source_id)
                        o.review_cycle_id, o.installation_id, o.repository_id, o.pr_number, o.head_sha, o.provider,
                        s.source_kind, s.source_id, s.severity, s.snapshot_id, i.id AS stable_finding_id
                    FROM review_evidence_sources s
                    JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
                    JOIN finding_identities i ON i.installation_id = o.installation_id
                        AND i.repository_id = o.repository_id AND i.pr_number = o.pr_number
                        AND i.provider = o.provider AND i.source_kind = s.source_kind AND i.source_id = s.source_id
                    WHERE o.installation_id = ? AND o.repository_id = ? AND o.pr_number = ? AND o.provider = ?
                        AND s.source_kind IN ('REVIEW', 'ISSUE_COMMENT')
                    ORDER BY o.review_cycle_id, s.source_kind, s.source_id, o.captured_at DESC
                ) latest WHERE true
                """ + UPDATE_FINDING, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), snapshot.provider());
    }

    /** Copy only stable metadata; deterministic data failures are not retryable. */
    private RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof Conflict) return new Conflict();
        if (failure instanceof Unavailable known) return new Unavailable(known.retryable());
        if (failure instanceof IllegalArgumentException || failure instanceof DataIntegrityViolationException) {
            return new Unavailable(false);
        }
        return new Unavailable(true);
    }

    /** Length-prefixed fields avoid delimiter collisions in untrusted paths, logins and provider IDs. */
    private String fingerprint(Snapshot snapshot, boolean includeFeedback) {
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
                if (includeFeedback) fields(out, s.feedback());
            }
            return Sha256.hex(bytes.toByteArray());
        } catch (IOException ignored) { throw new Unavailable(false); }
    }

    private void fields(DataOutputStream out, Object... values) throws IOException {
        for (Object value : values) {
            if (value == null) { out.writeInt(-1); continue; }
            byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length); out.write(encoded);
        }
    }
}
