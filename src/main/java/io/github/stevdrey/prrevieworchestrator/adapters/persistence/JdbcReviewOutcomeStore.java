package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Correlation and clean commit share the same serialized, authorized PR transaction. */
@Repository
public class JdbcReviewOutcomeStore implements ReviewOutcomeStore {
    private final JdbcTemplate jdbc;
    private final JdbcReviewStateStore states;
    private final ReviewOutcomeClassifier classifier = new ReviewOutcomeClassifier();

    /** Reuses the state store's bounded transaction, PR lock and failure sanitization. */
    public JdbcReviewOutcomeStore(JdbcTemplate jdbc, JdbcReviewStateStore states) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.states = Objects.requireNonNull(states);
    }

    /** A persisted snapshot plus its database-assigned ingestion time (null for rows ingested before V9). */
    private record Loaded(Snapshot snapshot, Instant ingestedAt) {}

    @Override
    public ReviewOutcome assess(Assessment assessment, Runnable authorize) {
        return states.locked(assessment.run().pr(), authorize, () -> classify(assessment, true));
    }

    @Override
    public PullRequestReview recordClean(Assessment assessment, UUID commandId, ReviewTransition.Gates gates, Runnable authorize) {
        return states.locked(assessment.run().pr(), authorize, () -> {
            rejectReplay(assessment, commandId);
            Run run = assessment.run();
            var transition = new ReviewTransition(commandId, assessment.expectedVersion(), run.headSha(),
                    run.stage().cleanAction(), null, gates);
            // CLEAN_FOR_SHA already requires no pending inline/general findings, so it replaces the default guard.
            var state = states.applyLocked(run.pr(), transition, () -> {
                if (classify(assessment, false).verdict() != Verdict.CLEAN_FOR_SHA) throw new ReviewConflict(GUARD_REJECTED);
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
                && Objects.equals(p.get("action"), run.stage().cleanAction().name())
                && Objects.equals(p.get("stage"), run.stage().name()) && Objects.equals(p.get("provider"), run.provider())
                && Objects.equals(p.get("evidence_provider"), run.evidenceProvider()) && Objects.equals(p.get("reviewer"), run.reviewer())
                && completion != null && run.equals(completion.run()) && completion.status() == Status.COMPLETED
                && completion.conclusion() == Conclusion.NO_FINDINGS && Objects.equals(p.get("review_source_id"), completion.reviewId());
        throw new ReviewConflict(exact ? DUPLICATE_TRANSITION : TRANSITION_ID_REUSED);
    }

    private ReviewOutcome classify(Assessment assessment, boolean recordProgress) {
        Run run = assessment.run();
        var pr = run.pr();
        var current = jdbc.queryForList("""
                SELECT head_sha, version, (SELECT occurred_at FROM pr_review_transitions t
                    WHERE t.installation_id = s.installation_id AND t.repository_id = s.repository_id
                        AND t.pr_number = s.pr_number AND t.action IN ('UPDATE_HEAD', 'CONFIRM_HEAD')
                    ORDER BY t.resulting_version DESC LIMIT 1) AS head_changed_at
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
                    AND r.head_epoch = ?
                    AND r.state IN ('COMMENT_CREATED', 'COMMENT_CREATED_HEAD_UNCONFIRMED')
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), run.requestId(), run.cycleId(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), run.headSha(), run.stage().name(),
                run.provider(), JdbcFixCorrelationStore.headEpoch(jdbc, pr, run.headSha()));
        Loaded loaded = loadSnapshot(assessment);
        Snapshot snapshot = loaded == null ? null : loaded.snapshot();
        Timestamp changed = (Timestamp) current.getFirst().get("head_changed_at");
        // Known retained blockers decide the outcome even when correlation is unavailable.
        var pending = JdbcGeneralFeedbackStore.pending(jdbc, pr, run.headSha());
        // Ingestion and head updates timestamp their writes while holding the same PR lock.
        // A timestamp tied with the head update cannot prove later ingestion.
        if (requests.size() != 1 || snapshot == null || loaded.ingestedAt() == null
                || loaded.ingestedAt().isBefore(requests.getFirst())
                || changed != null && !loaded.ingestedAt().isAfter(changed.toInstant())
                || snapshot.sources().stream().filter(s -> s.kind() == Kind.REVIEW
                    && assessment.completion() != null && s.id().equals(assessment.completion().reviewId()))
                    .anyMatch(s -> s.updatedAt().isBefore(requests.getFirst())
                        || changed != null && s.updatedAt().isBefore(changed.toInstant()))) {
            return classifier.classify(run, assessment.expectedVersion(), null, null, pending);
        }
        var dismissed = JdbcGeneralFeedbackStore.dismissedSources(jdbc, snapshot);
        // Valid only for the uniquely latest snapshot: retained occurrences link to it, and ingesting a changed
        // source revision reopens its finding.
        var closedInline = Set.copyOf(jdbc.queryForList("""
                SELECT source_id FROM findings WHERE evidence_snapshot_id = ? AND source_kind = 'REVIEW_COMMENT'
                    AND status IN ('DISMISSED', 'VERIFIED_FIXED')
                """, String.class, snapshot.id()));
        var outcome = classifier.classify(run, assessment.expectedVersion(), assessment.completion(), snapshot, pending,
                dismissed, closedInline);
        if (!recordProgress || outcome.verdict() != Verdict.ACTION_REQUIRED) return outcome;
        return classifier.findingFingerprint(run, assessment.completion(), snapshot, dismissed, closedInline)
                .map(fingerprint -> recordProgress(assessment, outcome, fingerprint)).orElse(outcome);
    }

    private ReviewOutcome recordProgress(Assessment assessment, ReviewOutcome outcome, String fingerprint) {
        Run run = assessment.run();
        String reviewId = assessment.completion().reviewId();
        int inserted = jdbc.update("""
                INSERT INTO review_round_progress (request_id, snapshot_id, review_source_id, finding_sha256)
                VALUES (?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING
                """, run.requestId(), assessment.snapshotId(), reviewId, fingerprint);
        if (inserted == 0) {
            var current = states.load(run.pr(), false).orElseThrow();
            return current.intervention() == Intervention.NO_PROGRESS ? outcome.noProgress(current.version()) : outcome;
        }
        if (!repeatsPriorRound(run, reviewId, fingerprint)) return outcome;
        var current = states.load(run.pr(), false).orElseThrow();
        var stopped = states.stopLocked(current, Intervention.NO_PROGRESS);
        return outcome.noProgress(stopped.version());
    }

    private boolean repeatsPriorRound(Run run, String reviewId, String fingerprint) {
        // Select the immediately preceding request even when it has no fingerprint.
        // Use the effective closure across cycles for the same stable finding and exact prior revision.
        var pr = run.pr();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH previous AS (
                    SELECT progress.snapshot_id, progress.review_source_id, progress.finding_sha256
                    FROM review_requests current JOIN review_requests prior
                        USING (installation_id, repository_id, pr_number, stage, provider)
                    LEFT JOIN review_round_progress progress ON progress.request_id = prior.id
                    WHERE current.id = ? AND prior.iteration < current.iteration
                    ORDER BY prior.iteration DESC LIMIT 1
                ),
                """ + JdbcGeneralFeedbackStore.EFFECTIVE_INLINE_FINDING_CTES + """
                SELECT EXISTS (
                    SELECT 1 FROM previous p
                    WHERE p.finding_sha256 = ? AND p.review_source_id <> ?
                    AND NOT EXISTS (
                        SELECT 1 FROM review_evidence_snapshots o
                        JOIN review_evidence_sources review ON review.snapshot_id = o.id
                            AND review.source_id = p.review_source_id
                        JOIN review_evidence_sources source ON source.snapshot_id = o.id
                            AND source.review_id = review.source_id AND source.author_login = review.author_login
                        JOIN finding_identities i ON i.installation_id = o.installation_id
                            AND i.repository_id = o.repository_id AND i.pr_number = o.pr_number AND i.provider = o.provider
                            AND i.source_kind = source.source_kind AND i.source_id = source.source_id
                        JOIN effective_inline_findings f ON f.stable_finding_id = i.id
                        WHERE o.id = p.snapshot_id AND source.reviewed_sha = o.head_sha
                            AND source.source_kind = 'REVIEW_COMMENT' AND source.reply_to_id IS NULL
                            AND source.thread_outdated = false AND f.status = 'VERIFIED_FIXED'
                            AND f.verified_at >= o.ingested_at
                            AND f.body_sha256 = source.body_sha256 AND f.feedback = source.feedback
                            AND f.source_updated_at = source.source_updated_at
                            AND NOT EXISTS (SELECT 1 FROM pending_inline_findings n WHERE n.stable_finding_id = i.id)
                    )
                )
                """, Boolean.class, run.requestId(), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                fingerprint, reviewId));
    }

    private Loaded loadSnapshot(Assessment assessment) {
        if (assessment.snapshotId() == null) return null;
        var run = assessment.run();
        var pr = run.pr();
        var captures = jdbc.queryForList("""
                SELECT captured_at, ingested_at, coalesce(observed_head_sha, head_sha) AS observed_head_sha
                FROM review_evidence_snapshots o
                WHERE o.id = ? AND o.review_cycle_id = ? AND o.installation_id = ? AND o.repository_id = ?
                    AND o.pr_number = ? AND o.head_sha = ? AND o.provider = ?
                    AND NOT EXISTS (SELECT 1 FROM review_evidence_snapshots n
                        WHERE n.installation_id = o.installation_id AND n.repository_id = o.repository_id
                            AND n.pr_number = o.pr_number AND n.provider = o.provider
                            AND n.id <> o.id AND n.captured_at >= o.captured_at)
                """, assessment.snapshotId(), run.cycleId(),
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
        var row = captures.getFirst();
        Timestamp ingested = (Timestamp) row.get("ingested_at");
        return new Loaded(new Snapshot(assessment.snapshotId(), run.cycleId(), pr, run.evidenceProvider(), run.headSha(),
                (String) row.get("observed_head_sha"),
                ((Timestamp) row.get("captured_at")).toInstant(), sources), ingested == null ? null : ingested.toInstant());
    }
}
