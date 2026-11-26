package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning.Preparation.Finished.*;
import static io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Status.*;

import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning;
import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning.Preparation;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Intent;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Assessment;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Comparison;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Head;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewIntentPolicy;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuse the lifecycle and correlation rules within the fenced inbox transaction. */
public final class JdbcSandboxReviewPlanning implements SandboxReviewPlanning.Commit {
    private static final String REQUEST_REVIEW = "REQUEST_REVIEW";
    private final JdbcTemplate jdbc;
    private final JdbcWorkQueue queue;
    private final JdbcReviewStateStore states;
    private final JdbcFixCorrelationStore fixes;
    private final ReviewIntentPolicy policy;
    private final int limit;

    public JdbcSandboxReviewPlanning(JdbcTemplate jdbc, JdbcWorkQueue queue, JdbcReviewStateStore states,
            JdbcFixCorrelationStore fixes, ReviewIntentPolicy policy, int limit) {
        if (limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid review planning limit");
        this.jdbc = jdbc;
        this.queue = queue;
        this.states = states;
        this.fixes = fixes;
        this.policy = policy;
        this.limit = limit;
    }

    @Override
    public Preparation prepare(Event event, Optional<PullRequestReview> expected, Head head, Runnable authorize) {
        requireEventHead(event, head);
        return queue.<Preparation>withEvent(event, authorize, () -> {
            var pr = head.pr();
            PostgresLocks.lockPr(jdbc, pr);
            if (!expected.equals(states.load(pr, false))) throw new ReviewConflict(ReviewConflict.Code.STALE_VERSION);
            boolean sync = SandboxReviewPlanning.isSynchronize(event);
            var current = expected.orElse(null);
            if (sync) {
                if (current == null) {
                    states.createLocked(pr, head.sha());
                    queue.finishEvent(event);
                    return PROCESSED;
                }
                fixes.observeHeadLocked(event.lease().id(), current, head);
                current = states.load(pr, false).orElseThrow();
                if (!head.sha().equals(current.headSha())) {
                    queue.finishEvent(event);
                    return PROCESSED;
                }
            } else if (current == null) {
                // Initialize the configured starting head only with the fresh CI observation at finish.
                return new Preparation.Ready(0, null);
            }
            var decision = fixes.assessLocked(pr, new Assessment(head, current.version(), false, null));
            if (decision.status() == WAITING_FOR_PUSHES) {
                queue.deferEvent(event, decision.retryAt());
                return DEFERRED;
            }
            if (decision.status() == COMPARISON_REQUIRED) {
                return new Preparation.Ready(current.version(), decision.baseSha());
            }
            if (!sync) return new Preparation.Ready(current.version(), null);
            queue.finishEvent(event);
            return PROCESSED;
        }).orElse(LEASE_LOST);
    }

    @Override
    public boolean finish(Event event, Preparation.Ready ready, Head head, CiEvidence ci, Comparison comparison,
            Runnable authorize) {
        requireEventHead(event, head);
        var pr = head.pr();
        if (!pr.equals(ci.pr()) || !head.sha().equals(ci.headSha())) {
            throw new IllegalArgumentException("CI evidence does not match the request head");
        }
        return queue.completeLocalEvent(event, authorize, () -> {
            PostgresLocks.lockPr(jdbc, pr);
            var current = states.load(pr, false).orElseGet(() -> states.createLocked(pr, head.sha()));
            if (current.version() != ready.version()) throw new ReviewConflict(ReviewConflict.Code.STALE_VERSION);
            if (!head.sha().equals(current.headSha())) throw new ReviewConflict(ReviewConflict.Code.STALE_HEAD);
            current = states.reconcileCiLocked(current.version(), ci);
            if (current.ci() != Ci.PASSED || comparison == null) return;

            var decision = fixes.assessLocked(pr, new Assessment(head, current.version(), false, comparison));
            if (decision.status() == ELIGIBLE || decision.status() == ALREADY_ELIGIBLE) plan(event, current);
        });
    }

    private void plan(Event event, PullRequestReview current) {
        var pr = current.pr();
        var intent = new Intent(pr.number(), current.headSha(), "BASELINE", "codex", REQUEST_REVIEW);
        boolean duplicate = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_commands WHERE command_key = ?)
                    OR EXISTS (SELECT 1 FROM review_requests
                        WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                        AND head_sha = ? AND stage = ? AND provider = ?)
                """, Boolean.class, intent.key(pr.scope()), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), intent.expectedHeadSha(), intent.stage(), intent.provider()));
        if (duplicate) return;

        // Count issued intents conservatively until a reservation for that same target already occupies a slot.
        long planned = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_commands o JOIN review_cycles c ON c.id = o.review_cycle_id
                WHERE c.installation_id = ? AND c.repository_id = ? AND c.pr_number = ? AND o.command_type = ?
                  AND NOT EXISTS (SELECT 1 FROM review_requests r WHERE
                    (r.installation_id, r.repository_id, r.pr_number, r.head_sha, r.stage, r.provider) =
                    (c.installation_id, c.repository_id, c.pr_number, o.expected_head_sha, c.stage, o.target_provider))
                """, Long.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), REQUEST_REVIEW);
        if (JdbcReviewRequestStore.usedIterations(jdbc, pr) + planned >= limit) {
            states.stopLocked(current, Intervention.ITERATION_LIMIT);
        } else if (policy.permits(pr, intent)) {
            queue.enqueue(event, intent);
        }
    }

    private void requireEventHead(Event event, Head head) {
        var pr = head.pr();
        if (!pr.scope().equals(event.scope()) || !head.sha().equals(event.observedHeadSha())
                || event.prNumber() != null && event.prNumber() != pr.number()) {
            throw new IllegalArgumentException("Head evidence does not match the claimed delivery");
        }
    }
}
