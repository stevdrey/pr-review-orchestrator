package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback.*;
import io.github.stevdrey.prrevieworchestrator.ports.GeneralFeedbackStore;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcGeneralFeedbackStore implements GeneralFeedbackStore {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcGeneralFeedbackStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        PostgresDeadlines.requireTransactionTimeout(jdbc, "general feedback");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(3);
    }

    private static final String LATEST = """
            SELECT f.*, s.body_sha256, s.source_updated_at, s.feedback
            FROM findings f
            JOIN review_evidence_sources s ON s.snapshot_id = f.evidence_snapshot_id AND s.source_id = f.source_id
            JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
            WHERE f.installation_id = ? AND f.repository_id = ? AND f.pr_number = ?
                AND f.source_kind IN ('REVIEW', 'ISSUE_COMMENT')
                AND NOT EXISTS (SELECT 1 FROM findings n
                    JOIN review_evidence_snapshots newer ON newer.id = n.evidence_snapshot_id
                    WHERE n.stable_finding_id = f.stable_finding_id AND newer.captured_at > o.captured_at)
            """;

    private static final String LAST_HEAD = """
            WITH last_head AS (SELECT coalesce(max(resulting_version), 0) AS version FROM pr_review_transitions
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND action = 'UPDATE_HEAD')
            """;
    private static final String VALID_DISMISSAL = """
            EXISTS (
                SELECT 1 FROM general_feedback_dispositions d
                JOIN review_evidence_sources s ON s.snapshot_id = d.snapshot_id AND s.source_id = d.source_id
                WHERE d.id = (SELECT id FROM general_feedback_dispositions
                    WHERE stable_finding_id = f.stable_finding_id ORDER BY disposition_order DESC LIMIT 1)
                AND d.action = 'DISMISS' AND d.head_sha = ?
                AND d.head_version >= (SELECT version FROM last_head)
                AND s.body_sha256 = f.body_sha256 AND s.source_updated_at = f.source_updated_at
                AND s.feedback = f.feedback)
            """;

    /** Called while holding the PR transaction lock. Missing/ambiguous feedback always blocks. */
    static boolean blocking(JdbcTemplate jdbc, PullRequestId pr, String head) {
        return pending(jdbc, pr, head) != ReviewOutcome.PendingFindings.CLEAR;
    }

    /** Retained inline findings, general feedback and legacy rows: the single guard for every transition path. */
    static ReviewOutcome.PendingFindings pending(JdbcTemplate jdbc, PullRequestId pr, String head) {
        // The last head change is constant per PR: compute it once, not per finding row.
        return ReviewOutcome.PendingFindings.valueOf(jdbc.queryForObject(LAST_HEAD + """
                , pending AS (
                SELECT f.feedback FROM (
                """ + LATEST + """
                ) f WHERE NOT
                """ + VALID_DISMISSAL + """
                UNION ALL
                    SELECT 'NEEDS_HUMAN' FROM findings legacy JOIN review_cycles c ON c.id = legacy.review_cycle_id
                    WHERE c.installation_id = ? AND c.repository_id = ? AND c.pr_number = ?
                        AND legacy.source_kind IN ('REVIEW', 'ISSUE_COMMENT') AND legacy.stable_finding_id IS NULL
                        AND legacy.status IN ('OPEN', 'ACKNOWLEDGED')
                UNION ALL
                    -- Missing candidates from a later snapshot do not remove previously retained inline findings.
                    SELECT 'ACTIONABLE' FROM findings f JOIN review_cycles c ON c.id = f.review_cycle_id
                    LEFT JOIN review_evidence_snapshots o ON o.id = f.evidence_snapshot_id
                    WHERE c.installation_id = ? AND c.repository_id = ? AND c.pr_number = ?
                        AND f.source_kind IN ('REVIEW_COMMENT', 'REVIEW_THREAD') AND f.status IN ('OPEN', 'ACKNOWLEDGED')
                        AND NOT EXISTS (SELECT 1 FROM findings n JOIN review_evidence_snapshots newer ON newer.id = n.evidence_snapshot_id
                            WHERE n.stable_finding_id = f.stable_finding_id AND newer.captured_at > o.captured_at))
                SELECT CASE WHEN bool_or(feedback = 'ACTIONABLE') THEN 'ACTIONABLE'
                    WHEN count(*) > 0 THEN 'NEEDS_HUMAN' ELSE 'CLEAR' END FROM pending
                """, String.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), head,
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number()));
    }

    /** Under the PR lock, match audited dismissals to the exact sources in this snapshot. */
    static Set<String> dismissedSources(JdbcTemplate jdbc, ReviewEvidence.Snapshot snapshot) {
        if (snapshot.sources().stream().noneMatch(s -> s.general() && s.candidate())) return Set.of();
        var pr = snapshot.pr();
        return Set.copyOf(jdbc.queryForList(LAST_HEAD + """
                SELECT f.source_id FROM (
                    SELECT s.source_id, i.id AS stable_finding_id, s.body_sha256, s.source_updated_at, s.feedback
                    FROM review_evidence_snapshots o
                    JOIN review_evidence_sources s ON s.snapshot_id = o.id
                    JOIN finding_identities i ON i.installation_id = o.installation_id
                        AND i.repository_id = o.repository_id AND i.pr_number = o.pr_number AND i.provider = o.provider
                        AND i.source_kind = s.source_kind AND i.source_id = s.source_id
                    WHERE o.id = ? AND o.installation_id = ? AND o.repository_id = ? AND o.pr_number = ?
                        AND o.head_sha = ? AND s.source_kind IN ('REVIEW', 'ISSUE_COMMENT') AND s.feedback <> 'NONE'
                ) f WHERE
                """ + VALID_DISMISSAL, String.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                snapshot.id(), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), snapshot.headSha(),
                snapshot.headSha()));
    }

    /** New pending feedback revokes already-recorded readiness in the same transaction. */
    static void invalidateClean(JdbcTemplate jdbc, PullRequestId pr) {
        var states = jdbc.queryForList("""
                SELECT * FROM pr_review_states WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                    AND baseline_clean_sha IS NOT NULL
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
        if (states.isEmpty()) return;
        var state = states.getFirst();
        String head = (String) state.get("head_sha");
        if (!blocking(jdbc, pr, head)) return;
        long version = ((Number) state.get("version")).longValue();
        boolean paused = "PAUSED".equals(state.get("state"));
        String next = paused ? "PAUSED" : "ACTION_REQUIRED";
        jdbc.update("""
                UPDATE pr_review_states SET version = version + 1, state = ?, resume_state = ?,
                    baseline_clean_sha = NULL, deep_clean_sha = NULL
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, next, paused ? "ACTION_REQUIRED" : null, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
        jdbc.update("""
                INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
                    expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state)
                VALUES (?, ?, ?, ?, ?, ?, 'BLOCKING_FINDINGS', ?, ?, ?)
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), UUID.randomUUID(),
                version, head, version + 1, head, next);
    }

    @Override
    public boolean dispose(PullRequestId pr, Disposition command, Runnable authorize) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    PostgresLocks.lockPr(jdbc, pr);
                    return persist(pr, command);
                } catch (RuntimeException failure) { throw sanitized(failure); }
            }));
        } catch (RuntimeException failure) { throw sanitized(failure); }
    }

    private boolean persist(PullRequestId pr, Disposition c) {
        var previous = jdbc.queryForList("SELECT * FROM general_feedback_dispositions WHERE id = ?", c.id());
        if (!previous.isEmpty()) {
            var p = previous.getFirst();
            if (!Objects.equals(p.get("stable_finding_id"), c.findingId())
                    || !Objects.equals(p.get("snapshot_id"), c.expectedSnapshotId())
                    || !Objects.equals(p.get("installation_id"), pr.scope().installationId())
                    || !Objects.equals(p.get("repository_id"), pr.scope().repositoryId())
                    || !Objects.equals(p.get("pr_number"), pr.number())
                    || !Objects.equals(p.get("head_sha"), c.expectedHeadSha())
                    || !Objects.equals(p.get("head_version"), c.expectedVersion())
                    || !Objects.equals(p.get("action"), c.action().name())
                    || !Objects.equals(p.get("actor"), c.actor()) || !Objects.equals(p.get("reason"), c.reason())
                    || !Objects.equals(p.get("evidence_sha"), c.evidenceSha())) throw new ReviewEvidence.Conflict();
            return false;
        }
        var heads = jdbc.queryForList("""
                SELECT head_sha, version FROM pr_review_states WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
        if (heads.isEmpty() || !heads.getFirst().get("head_sha").equals(c.expectedHeadSha())
                || !heads.getFirst().get("version").equals(c.expectedVersion())) throw new ReviewEvidence.Conflict();
        var latest = jdbc.queryForList("SELECT * FROM (" + LATEST + ") f WHERE stable_finding_id = ?",
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), c.findingId());
        if (latest.size() != 1) throw new ReviewEvidence.Conflict();
        var f = latest.getFirst();
        if (!c.expectedSnapshotId().equals(f.get("evidence_snapshot_id"))) throw new ReviewEvidence.Conflict();
        // The PR lock does not cover a concurrent reuse of this UUID on another PR: report that as a conflict.
        int inserted = jdbc.update("""
                INSERT INTO general_feedback_dispositions (id, stable_finding_id, installation_id, repository_id,
                    pr_number, provider, source_kind, source_id, snapshot_id, head_sha, head_version, action, actor, reason, evidence_sha)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, c.id(), c.findingId(), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                f.get("source_provider"), f.get("source_kind"), f.get("source_id"), c.expectedSnapshotId(),
                c.expectedHeadSha(), c.expectedVersion(), c.action().name(), c.actor(), c.reason(), c.evidenceSha());
        if (inserted != 1) throw new ReviewEvidence.Conflict();
        // A fix claim is only acknowledged. It cannot manufacture VERIFIED_FIXED or verified_at.
        jdbc.update("""
                UPDATE findings SET status = ?, disposition_actor = ?, disposition_reason = ?,
                    evidence_sha = NULL, verified_at = NULL WHERE id = ?
                """, c.action() == Action.DISMISS ? "DISMISSED" : "ACKNOWLEDGED", c.actor(), c.reason(), f.get("id"));
        invalidateClean(jdbc, pr);
        return true;
    }

    private static RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof ReviewEvidence.Conflict) return new ReviewEvidence.Conflict();
        if (failure instanceof ReviewEvidence.Unavailable known) return new ReviewEvidence.Unavailable(known.retryable());
        return new ReviewEvidence.Unavailable(!(failure instanceof IllegalArgumentException
                || failure instanceof org.springframework.dao.DataIntegrityViolationException));
    }
}
