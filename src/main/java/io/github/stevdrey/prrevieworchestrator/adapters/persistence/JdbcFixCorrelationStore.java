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
            var previous = jdbc.query("""
                    SELECT action IN ('UPDATE_HEAD', 'CONFIRM_HEAD') AND resulting_head_sha = ?
                    FROM pr_review_transitions WHERE installation_id = ? AND repository_id = ?
                        AND pr_number = ? AND transition_id = ?
                    """, (rs, row) -> rs.getBoolean(1), head.sha(), pr.scope().installationId(),
                    pr.scope().repositoryId(), pr.number(), delivery);
            if (!previous.isEmpty()) {
                if (!previous.getFirst()) throw new ReviewConflict(TRANSITION_ID_REUSED);
                return false;
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
        if (requested(pr, head.sha())) return new Decision(ALREADY_REQUESTED);

        return recordCandidate(pr, timeline);
    }

    private Timeline loadTimeline(PullRequestId pr) {
        return jdbc.query("""
                SELECT s.head_sha, s.version, s.state, fix.resulting_head_sha AS base_sha,
                    fix.resulting_version AS base_version, push.resulting_version AS update_version,
                    push.occurred_at AS last_push, clock_timestamp() AS now
                FROM pr_review_states s
                LEFT JOIN LATERAL (
                    SELECT t.resulting_head_sha, t.resulting_version FROM pr_review_transitions t
                    WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
                        AND t.action IN ('BLOCKING_FINDINGS', 'AWAIT_FIX')
                    ORDER BY t.resulting_version DESC LIMIT 1
                ) fix ON true
                LEFT JOIN LATERAL (
                    SELECT t.resulting_version, t.occurred_at FROM pr_review_transitions t
                    WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
                        AND t.action = 'UPDATE_HEAD'
                    ORDER BY t.resulting_version DESC LIMIT 1
                ) push ON true
                WHERE s.installation_id = ? AND s.repository_id = ? AND s.pr_number = ?
                """, (rs, row) -> {
            Timestamp lastPush = rs.getTimestamp("last_push");
            return new Timeline(rs.getString("head_sha"), rs.getLong("version"), State.valueOf(rs.getString("state")),
                    rs.getString("base_sha"), rs.getLong("base_version"), rs.getLong("update_version"),
                    lastPush == null ? null : lastPush.toInstant().plus(QUIET_PERIOD), rs.getTimestamp("now").toInstant());
        }, pr.scope().installationId(), pr.scope().repositoryId(), pr.number()).stream().findFirst()
                .orElseThrow(() -> new ReviewConflict(NOT_FOUND));
    }

    private boolean requested(PullRequestId pr, String head) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM review_requests
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND head_sha = ? AND stage = 'BASELINE')
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), head));
    }

    private Decision recordCandidate(PullRequestId pr, Timeline timeline) {
        var existing = jdbc.query("""
                SELECT id FROM fix_review_candidates
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND head_sha = ?
                """, (rs, row) -> rs.getObject(1, UUID.class),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), timeline.headSha());
        if (!existing.isEmpty()) return new Decision(ALREADY_ELIGIBLE, timeline.baseSha(), null, existing.getFirst());

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fix_review_candidates
                    (id, installation_id, repository_id, pr_number, head_sha, base_sha, head_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                timeline.headSha(), timeline.baseSha(), timeline.updateVersion());
        return new Decision(ELIGIBLE, timeline.baseSha(), null, id);
    }
}
