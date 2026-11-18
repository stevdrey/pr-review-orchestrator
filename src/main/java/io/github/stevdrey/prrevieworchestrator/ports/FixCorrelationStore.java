package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Assessment;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Decision;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Head;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import java.util.UUID;

public interface FixCorrelationStore {
    /** Journal a fresh synchronize observation once. True only when it actually changes the head. */
    boolean observeHead(UUID delivery, PullRequestReview expected, Head current, Runnable authorize);

    /** Evaluate and record eligibility under the same authorized PR transaction. */
    Decision assess(Assessment assessment, Runnable authorize);
}
