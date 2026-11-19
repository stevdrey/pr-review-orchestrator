package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import java.util.Optional;

/** State and transition-key journal commit together; authorization runs inside each transaction. */
public interface ReviewStateStore {
    PullRequestReview reconcileCi(long expectedVersion, CiEvidence evidence, Runnable authorize);
    PullRequestReview create(PullRequestId pr, String headSha, Runnable authorize);
    Optional<PullRequestReview> find(PullRequestId pr, Runnable authorize);
    PullRequestReview apply(PullRequestId pr, ReviewTransition transition, Runnable authorize);
}
