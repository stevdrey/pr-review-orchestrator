package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Status.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;

import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import io.github.stevdrey.prrevieworchestrator.ports.FixCorrelationStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFixCorrelationStore implements FixCorrelationStore {
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final JdbcReviewStateStore states;

    private record Timeline(String headSha, long version, State state, String baseSha,
            long baseVersion, long updateVersion, Instant retryAt, Instant now) {}

    public JdbcFixCorrelationStore(JdbcTemplate jdbc, JdbcReviewStateStore states) {
        this.jdbc = jdbc;
        this.states = states;
    }

    @Override
    public boolean observeHead(UUID delivery, PullRequestReview expected, Head head, Runnable authorize) {
        var pr = head.pr();
        return states.locked(pr, authorize, () -> {
            // Not applyLocked's replay check: a redelivery is re-read after the first commit, so its expected
            // version no longer matches and would look like a reused ID.
            var previous = jdbc.query("""
                    SELECT action IN ('UPDATE_HEAD', 'CONFIRM_HEAD') AND resulting_head_sha = ?, action = 'UPDATE_HEAD'
                    FROM pr_review_transitions WHERE installation_id = ? AND repository_id = ?
                        AND pr_number = ? AND transition_id = ?
                    """, (rs, row) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}, head.sha(), pr.scope().installationId(),
                    pr.scope().repositoryId(), pr.number(), delivery);
            if (!previous.isEmpty()) {
                if (!previous.getFirst()[0]) throw new ReviewConflict(TRANSITION_ID_REUSED);
                return previous.getFirst()[1];
            }

            boolean changed = !expected.headSha().equals(head.sha());
            var action = changed ? ReviewTransition.Action.UPDATE_HEAD : ReviewTransition.Action.CONFIRM_HEAD;
            var gates = new ReviewTransition.Gates(head.open(), head.draft(), false, false, false, ReviewTransition.Ci.UNKNOWN);
            states.applyLocked(pr, new ReviewTransition(delivery, expected.version(), expected.headSha(),
                    action, changed ? head.sha() : null, gates), null);
            return changed;
        });
    }

    @Override
    public Decision assess(Assessment assessment, Runnable authorize) {
        var pr = assessment.head().pr();
        return states.locked(pr, authorize, () -> assessLocked(pr, assessment));
    }

    private Decision assessLocked(PullRequestId pr, Assessment assessment) {
        Head head = assessment.head();
        Timeline timeline = loadTimeline(pr);
        if (timeline.version() != assessment.expectedVersion()) throw new ReviewConflict(STALE_VERSION);
        if (!timeline.headSha().equals(head.sha())) throw new ReviewConflict(STALE_HEAD);

        if (timeline.baseSha() == null) return new Decision(NO_REMEDIATION);
        if (timeline.updateVersion() <= timeline.baseVersion() || head.sha().equals(timeline.baseSha())) {
            return new Decision(NO_NEW_CODE);
        }
        if (timeline.state() != State.WAITING_FOR_REVIEW || !head.open() || head.draft() || assessment.humanHold()) {
            return new Decision(NOT_REVIEWABLE);
        }
        if (timeline.now().isBefore(timeline.retryAt())) {
            return new Decision(WAITING_FOR_PUSHES, timeline.baseSha(), timeline.retryAt(), null);
        }
        Comparison comparison = assessment.comparison();
        if (comparison == null || !timeline.baseSha().equals(comparison.baseSha()) || !head.sha().equals(comparison.headSha())) {
            return new Decision(COMPARISON_REQUIRED, timeline.baseSha(), null, null);
        }
        if (!comparison.changed()) return new Decision(NO_NEW_CODE);

        var findings = JdbcGeneralFeedbackStore.findingGates(jdbc, pr, head.sha());
        if (findings.generalNeedsDisposition()) return new Decision(GENERAL_DISPOSITION_REQUIRED);
        if (!findings.inlinePending() && !findings.generalPending()) return new Decision(NO_PENDING_FINDINGS);
        long epoch = headEpoch(jdbc, pr, head.sha());
        if (requested(pr, head.sha(), epoch)) return new Decision(ALREADY_REQUESTED);

        return recordCandidate(pr, timeline, epoch);
    }

    private Timeline loadTimeline(PullRequestId pr) {
        return jdbc.query("""
                SELECT s.head_sha, s.version, s.state, j.base_sha, j.base_version, j.update_version, j.last_push,
                    clock_timestamp() AS now
                FROM pr_review_states s
                -- One pass over the journal: the last fix base, head update and head observation.
                LEFT JOIN LATERAL (
                    SELECT (array_agg(t.resulting_head_sha ORDER BY t.resulting_version DESC)
                               FILTER (WHERE t.action IN ('BLOCKING_FINDINGS', 'AWAIT_FIX')))[1] AS base_sha,
                           max(t.resulting_version) FILTER (WHERE t.action IN ('BLOCKING_FINDINGS', 'AWAIT_FIX')) AS base_version,
                           max(t.resulting_version) FILTER (WHERE t.action = 'UPDATE_HEAD') AS update_version,
                           (array_agg(t.occurred_at ORDER BY t.resulting_version DESC)
                               FILTER (WHERE t.action IN ('UPDATE_HEAD', 'CONFIRM_HEAD')))[1] AS last_push
                    FROM pr_review_transitions t
                    WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
                        AND t.action IN ('BLOCKING_FINDINGS', 'AWAIT_FIX', 'UPDATE_HEAD', 'CONFIRM_HEAD')
                ) j ON true
                WHERE s.installation_id = ? AND s.repository_id = ? AND s.pr_number = ?
                """, (rs, row) -> {
            Timestamp lastPush = rs.getTimestamp("last_push");
            return new Timeline(rs.getString("head_sha"), rs.getLong("version"), State.valueOf(rs.getString("state")),
                    rs.getString("base_sha"), rs.getLong("base_version"), rs.getLong("update_version"),
                    lastPush == null ? null : lastPush.toInstant().plus(QUIET_PERIOD), rs.getTimestamp("now").toInstant());
        }, pr.scope().installationId(), pr.scope().repositoryId(), pr.number()).stream().findFirst()
                .orElseThrow(() -> new ReviewConflict(NOT_FOUND));
    }

    private boolean requested(PullRequestId pr, String head, long epoch) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM review_requests
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND head_sha = ? AND stage = 'BASELINE'
                        AND coalesce(reconciled_head_epoch, head_epoch) = ?)
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), head,
                epoch));
    }

    /** Epoch 0 on the first visit. A same-head confirmation or a return after departure sets a journal version. */
    static long headEpoch(JdbcTemplate jdbc, PullRequestId pr, String head) {
        // Caller holds the PR lock. The first departure also records the initial SHA, even when no other action preceded it.
        return jdbc.queryForObject("""
                WITH visits AS (
                    SELECT min(resulting_version) FILTER (
                               WHERE action = 'UPDATE_HEAD' AND expected_head_sha = ?) AS first_departure,
                           max(resulting_version) FILTER (
                               WHERE action = 'UPDATE_HEAD' AND resulting_head_sha = ?) AS last_arrival,
                           max(resulting_version) FILTER (
                               WHERE action = 'CONFIRM_HEAD' AND resulting_head_sha = ?) AS last_confirmation
                    FROM pr_review_transitions
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                        AND action IN ('UPDATE_HEAD', 'CONFIRM_HEAD')
                )
                SELECT greatest(0, last_confirmation,
                    CASE WHEN last_arrival > first_departure THEN last_arrival END)
                FROM visits
                """, Long.class, head, head, head, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
    }

    private Decision recordCandidate(PullRequestId pr, Timeline timeline, long epoch) {
        var existing = jdbc.query("""
                SELECT id FROM fix_review_candidates
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND head_sha = ? AND head_version = ?
                """, (rs, row) -> rs.getObject(1, UUID.class),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), timeline.headSha(),
                timeline.updateVersion());
        if (!existing.isEmpty()) return new Decision(ALREADY_ELIGIBLE, timeline.baseSha(), null, existing.getFirst(), epoch);

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fix_review_candidates
                    (id, installation_id, repository_id, pr_number, head_sha, base_sha, head_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                timeline.headSha(), timeline.baseSha(), timeline.updateVersion());
        return new Decision(ELIGIBLE, timeline.baseSha(), null, id, epoch);
    }
}
