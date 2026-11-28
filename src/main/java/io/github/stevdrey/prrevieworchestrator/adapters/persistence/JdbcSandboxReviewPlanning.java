package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning.Preparation.Finished.*;
import static io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Status.*;
import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.REQUEST_REVIEW;

import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning;
import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning.Preparation;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Intent;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Assessment;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Comparison;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Head;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewIntentPolicy;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuse the lifecycle and correlation rules within the fenced inbox transaction. */
public final class JdbcSandboxReviewPlanning implements SandboxReviewPlanning.Commit {
    private final JdbcTemplate jdbc;
    private final JdbcWorkQueue queue;
    private final JdbcReviewStateStore states;
    private final JdbcFixCorrelationStore fixes;
    private final ReviewIntentPolicy policy;
    private final JdbcRepositoryPolicy repositoryPolicy;
    private final int limit;

    public JdbcSandboxReviewPlanning(JdbcTemplate jdbc, JdbcWorkQueue queue, JdbcReviewStateStore states,
            JdbcFixCorrelationStore fixes, ReviewIntentPolicy policy, int limit) {
        if (limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid review planning limit");
        this.jdbc = jdbc;
        this.queue = queue;
        this.states = states;
        this.fixes = fixes;
        this.policy = policy;
        this.repositoryPolicy = new JdbcRepositoryPolicy(jdbc);
        this.limit = limit;
    }

    @Override
    public Preparation prepare(Event event, Optional<PullRequestReview> expected, Head head, Runnable authorize) {
        requireEventHead(event, head);
        return queue.<Preparation>withEvent(event, authorize, () -> {
            var pr = head.pr();
            PostgresLocks.lockPr(jdbc, pr);
            boolean sync = SandboxReviewPlanning.isSynchronize(event);
            var loaded = states.load(pr, false);
            // A synchronize only loses to a newer head observation; unrelated writes must not exhaust its retries.
            if (sync ? loaded.isPresent() != expected.isPresent()
                    || expected.isPresent() && headObservedAfter(pr, expected.orElseThrow().version())
                    : !expected.equals(loaded)) {
                throw new ReviewConflict(ReviewConflict.Code.STALE_VERSION);
            }
            var current = loaded.orElse(null);
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
                // If a due assessment sees a newer quiet-period deadline, the newer
                // synchronize observation already scheduled its own assessment.
                if (!SandboxReviewPlanning.isAssessment(event)) {
                    queue.scheduleReviewAssessment(event, pr, head.sha(), decision.retryAt());
                }
                queue.finishEvent(event);
                return PROCESSED;
            }
            if (decision.status() == COMPARISON_REQUIRED) {
                return new Preparation.Ready(current.version(), decision.baseSha());
            }
            // An accepted synchronize also reads current-head CI: CI deliveries that beat it were superseded.
            return new Preparation.Ready(current.version(), null);
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
            if (decision.status() == ELIGIBLE || decision.status() == ALREADY_ELIGIBLE) {
                plan(event, current, decision.headEpoch());
            }
        });
    }

    private void plan(Event event, PullRequestReview current, long headEpoch) {
        var pr = current.pr();
        var intent = new Intent(pr.number(), current.headSha(), "BASELINE", "codex", REQUEST_REVIEW);
        var snapshot = repositoryPolicy.lockCurrent(pr.scope());
        if (!snapshot.permits(intent.stage(), intent.provider(), repositoryPolicy.now())) return;
        boolean duplicate = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_commands WHERE command_key = ?)
                    OR EXISTS (SELECT 1 FROM review_requests
                        WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                        AND head_sha = ? AND stage = ? AND provider = ?)
                """, Boolean.class, intent.key(pr.scope()), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), intent.expectedHeadSha(), intent.stage(), intent.provider()));
        if (duplicate) return;

        if (repositoryPolicy.occupied(pr, null) >= Math.min(limit, snapshot.policy().iterationCap())) {
            states.stopLocked(current, Intervention.ITERATION_LIMIT);
        } else if (policy.permits(pr, intent)) {
            queue.enqueue(event, intent, headEpoch, snapshot.version());
        }
    }

    private boolean headObservedAfter(PullRequestId pr, long version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pr_review_transitions
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                        AND action IN ('UPDATE_HEAD', 'CONFIRM_HEAD') AND resulting_version > ?)
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), version));
    }

    private void requireEventHead(Event event, Head head) {
        var pr = head.pr();
        if (!pr.scope().equals(event.scope()) || !head.sha().equals(event.observedHeadSha())
                || event.prNumber() != null && event.prNumber() != pr.number()) {
            throw new IllegalArgumentException("Head evidence does not match the claimed delivery");
        }
    }
}
