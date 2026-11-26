package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Intent;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;

/** Trusted local policy for the exact intent, including operator authorization and a configured budget stop.
 * Runs under the PR lock. Implementations must be bounded and perform no external calls.
 * A planning decision grants no send permit. Dispatch must independently enforce approval and reserve budget.
 */
@FunctionalInterface
public interface ReviewIntentPolicy {
    boolean permits(PullRequestId pr, Intent intent);
}
