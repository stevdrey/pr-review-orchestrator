package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Correlation and clean commit share the same serialized, authorized PR transaction. */
@Repository
public class JdbcReviewOutcomeStore implements ReviewOutcomeStore {
    private final JdbcTemplate jdbc;
    private final JdbcReviewStateStore states;
    private final TransactionTemplate transactions;
    private final ReviewOutcomeClassifier classifier = new ReviewOutcomeClassifier();

    public JdbcReviewOutcomeStore(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcReviewStateStore states) {
        this.jdbc = jdbc;
        this.states = states;
        PostgresDeadlines.requireTransactionTimeout(jdbc, "review outcome");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(3);
    }

    @Override
    public ReviewOutcome assess(Assessment assessment, Runnable authorize) {
        return transaction(assessment.run().pr(), authorize, () -> classify(assessment));
    }

    @Override
    public PullRequestReview recordClean(Assessment assessment, UUID commandId, ReviewTransition.Gates gates, Runnable authorize) {
        return transaction(assessment.run().pr(), authorize, () -> {
            rejectReplay(assessment, commandId);
            Run run = assessment.run();
            var transition = new ReviewTransition(commandId, assessment.expectedVersion(), run.headSha(),
                    run.stage() == Stage.BASELINE ? ReviewTransition.Action.BASELINE_CLEAN : ReviewTransition.Action.DEEP_CLEAN,
                    null, gates);
            var state = states.applyLocked(run.pr(), transition, () -> {
                if (classify(assessment).verdict() != Verdict.CLEAN_FOR_SHA) throw new ReviewConflict(GUARD_REJECTED);
            });
            jdbc.update("""
                    INSERT INTO review_clean_evidence (installation_id, repository_id, pr_number, transition_id,
                        request_id, cycle_id, snapshot_id, head_sha, stage, provider, evidence_provider, review_source_id, reviewer)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, run.pr().scope().installationId(), run.pr().scope().repositoryId(), run.pr().number(), commandId,
                    run.requestId(), run.cycleId(), assessment.snapshotId(), run.headSha(), run.stage().name(),
                    run.provider(), run.evidenceProvider(), assessment.completion().reviewId(), run.reviewer());
            return state;
        });
    }

    private void rejectReplay(Assessment assessment, UUID commandId) {
        Run run = assessment.run();
        var previous = jdbc.queryForList("""
                SELECT t.expected_version, t.expected_head_sha, t.action, e.*
                FROM pr_review_transitions t LEFT JOIN review_clean_evidence e
                    USING (installation_id, repository_id, pr_number, transition_id)
                WHERE t.installation_id = ? AND t.repository_id = ? AND t.pr_number = ? AND t.transition_id = ?
                """, run.pr().scope().installationId(), run.pr().scope().repositoryId(), run.pr().number(), commandId);
        if (previous.isEmpty()) return;
        var p = previous.getFirst();
        Completion completion = assessment.completion();
        boolean exact = Objects.equals(p.get("request_id"), run.requestId())
                && Objects.equals(p.get("cycle_id"), run.cycleId()) && Objects.equals(p.get("snapshot_id"), assessment.snapshotId())
                && Objects.equals(p.get("head_sha"), run.headSha()) && Objects.equals(p.get("expected_head_sha"), run.headSha())
                && Objects.equals(p.get("expected_version"), assessment.expectedVersion())
                && Objects.equals(p.get("action"), run.stage() == Stage.BASELINE ? "BASELINE_CLEAN" : "DEEP_CLEAN")
                && Objects.equals(p.get("stage"), run.stage().name()) && Objects.equals(p.get("provider"), run.provider())
                && Objects.equals(p.get("evidence_provider"), run.evidenceProvider()) && Objects.equals(p.get("reviewer"), run.reviewer())
                && completion != null && run.equals(completion.run()) && completion.status() == Status.COMPLETED
                && completion.conclusion() == Conclusion.NO_FINDINGS && Objects.equals(p.get("review_source_id"), completion.reviewId());
        throw new ReviewConflict(exact ? DUPLICATE_TRANSITION : TRANSITION_ID_REUSED);
    }

    private <T> T transaction(PullRequestId pr, Runnable authorize, Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, Duration.ofSeconds(3));
                    authorize.run();
                    PostgresLocks.lockPr(jdbc, pr);
                    return operation.get();
                } catch (RuntimeException failure) { throw sanitized(failure); }
            });
        } catch (RuntimeException failure) { throw sanitized(failure); }
    }

    private ReviewOutcome classify(Assessment assessment) {
        Run run = assessment.run();
        var pr = run.pr();
        var current = jdbc.queryForList("""
                SELECT head_sha, version, (SELECT max(occurred_at) FROM pr_review_transitions t
                    WHERE t.installation_id = s.installation_id AND t.repository_id = s.repository_id
                        AND t.pr_number = s.pr_number AND t.action = 'UPDATE_HEAD') AS head_changed_at
                FROM pr_review_states s WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
        if (current.isEmpty()) throw new ReviewConflict(NOT_FOUND);
        if (((Number) current.getFirst().get("version")).longValue() != assessment.expectedVersion()) throw new ReviewConflict(STALE_VERSION);
        if (!current.getFirst().get("head_sha").equals(run.headSha())) throw new ReviewConflict(STALE_HEAD);

        // A receipt only proves a request was posted, never that the provider completed it.
        var requests = jdbc.query("""
                SELECT r.created_at FROM review_requests r JOIN review_cycles c
                    ON c.installation_id = r.installation_id AND c.repository_id = r.repository_id
                    AND c.pr_number = r.pr_number AND c.head_sha = r.head_sha
                    AND c.stage = r.stage AND c.review_provider = r.provider
                WHERE r.id = ? AND c.id = ? AND r.installation_id = ? AND r.repository_id = ? AND r.pr_number = ?
                    AND r.head_sha = ? AND r.stage = ? AND r.provider = ?
                    AND r.state IN ('COMMENT_CREATED', 'COMMENT_CREATED_HEAD_UNCONFIRMED')
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), run.requestId(), run.cycleId(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), run.headSha(), run.stage().name(), run.provider());
        Snapshot snapshot = loadSnapshot(assessment);
        PendingFindings pending = pending(pr, run.headSha());
        Timestamp changed = (Timestamp) current.getFirst().get("head_changed_at");
        // Do not reuse a pre-request review/read or an older observation after any newer evidence.
        if (requests.size() != 1 || snapshot == null || snapshot.capturedAt().isBefore(requests.getFirst())
                || changed != null && snapshot.capturedAt().isBefore(changed.toInstant())
                || snapshot.sources().stream().filter(s -> s.kind() == Kind.REVIEW
                    && assessment.completion() != null && s.id().equals(assessment.completion().reviewId()))
                    .anyMatch(s -> s.updatedAt().isBefore(requests.getFirst())
                        || changed != null && s.updatedAt().isBefore(changed.toInstant()))) {
            return classifier.classify(run, assessment.expectedVersion(), null, null, PendingFindings.UNKNOWN);
        }
        return classifier.classify(run, assessment.expectedVersion(), assessment.completion(), snapshot, pending);
    }

    private Snapshot loadSnapshot(Assessment assessment) {
        if (assessment.snapshotId() == null) return null;
        var run = assessment.run();
        var pr = run.pr();
        var captures = jdbc.query("""
                SELECT captured_at FROM review_evidence_snapshots o
                WHERE o.id = ? AND o.review_cycle_id = ? AND o.installation_id = ? AND o.repository_id = ?
                    AND o.pr_number = ? AND o.head_sha = ? AND o.provider = ?
                    AND NOT EXISTS (SELECT 1 FROM review_evidence_snapshots n
                        WHERE n.installation_id = o.installation_id AND n.repository_id = o.repository_id
                            AND n.pr_number = o.pr_number AND n.provider = o.provider
                            AND n.id <> o.id AND n.captured_at >= o.captured_at)
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), assessment.snapshotId(), run.cycleId(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), run.headSha(), run.evidenceProvider());
        if (captures.size() != 1) return null;
        var sources = jdbc.query("SELECT * FROM review_evidence_sources WHERE snapshot_id = ?", (rs, row) -> {
            Timestamp updated = rs.getTimestamp("source_updated_at");
            return new Source(rs.getString("source_id"), Kind.valueOf(rs.getString("source_kind")),
                    rs.getString("review_id"), rs.getString("thread_id"), rs.getString("reply_to_id"), rs.getString("author_login"),
                    rs.getString("reviewed_sha"), rs.getString("original_sha"), rs.getString("body_sha256"),
                    updated == null ? null : updated.toInstant(), rs.getString("review_state"),
                    rs.getObject("thread_resolved", Boolean.class), rs.getObject("thread_outdated", Boolean.class),
                    rs.getString("path"), rs.getObject("line", Integer.class), rs.getObject("original_line", Integer.class),
                    Severity.valueOf(rs.getString("severity")), Feedback.valueOf(rs.getString("feedback")));
        }, assessment.snapshotId());
        return new Snapshot(assessment.snapshotId(), run.cycleId(), pr, run.evidenceProvider(), run.headSha(), captures.getFirst(), sources);
    }

    private PendingFindings pending(PullRequestId pr, String head) {
        // Missing candidates from a later snapshot do not remove previously retained inline findings.
        boolean inline = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM findings f JOIN review_cycles c ON c.id = f.review_cycle_id
                    LEFT JOIN review_evidence_snapshots o ON o.id = f.evidence_snapshot_id
                    WHERE c.installation_id = ? AND c.repository_id = ? AND c.pr_number = ?
                        AND f.source_kind IN ('REVIEW_COMMENT', 'REVIEW_THREAD') AND f.status IN ('OPEN', 'ACKNOWLEDGED')
                        AND NOT EXISTS (SELECT 1 FROM findings n JOIN review_evidence_snapshots newer ON newer.id = n.evidence_snapshot_id
                            WHERE n.stable_finding_id = f.stable_finding_id AND newer.captured_at > o.captured_at))
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number()));
        if (inline) return PendingFindings.ACTIONABLE;
        return JdbcGeneralFeedbackStore.pending(jdbc, pr, head);
    }

    private RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof ReviewConflict conflict) return new ReviewConflict(conflict.code());
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof ReviewStateUnavailable known) return new ReviewStateUnavailable(known.retryable());
        return new ReviewStateUnavailable(!(failure instanceof IllegalArgumentException
                || failure instanceof org.springframework.dao.DataIntegrityViolationException));
    }
}
