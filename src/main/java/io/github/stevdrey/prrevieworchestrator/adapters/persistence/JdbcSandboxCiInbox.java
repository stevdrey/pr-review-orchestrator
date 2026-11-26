package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Journal the observation in the same fenced transaction that completes the original inbox row. */
public final class JdbcSandboxCiInbox implements SandboxCiReconciler.Commit {
    private final JdbcTemplate jdbc;
    private final JdbcWorkQueue queue;
    private final JdbcReviewStateStore states;

    public JdbcSandboxCiInbox(JdbcTemplate jdbc, JdbcWorkQueue queue, JdbcReviewStateStore states) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.states = states;
    }

    @Override
    public boolean reconcile(Event event, Optional<PullRequestReview> expected, CiEvidence evidence, Runnable authorize) {
        var pr = evidence.pr();
        if (!event.scope().equals(pr.scope()) || !event.observedHeadSha().equals(evidence.headSha())
                || !event.lease().id().equals(evidence.id())) {
            throw new IllegalArgumentException("CI observation does not match the claimed delivery");
        }
        return queue.completeLocalEvent(event, authorize, () -> {
            PostgresLocks.lockPr(jdbc, pr);
            var current = states.load(pr, false);
            if (expected.isEmpty() && current.isEmpty()) {
                states.createLocked(pr, evidence.headSha());
            } else if (!expected.equals(current)) {
                throw new ReviewConflict(ReviewConflict.Code.STALE_VERSION);
            }
            states.reconcileCiLocked(expected.map(PullRequestReview::version).orElse(0L), evidence);
        });
    }
}
