package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;

/** Request acceptance is not a completed or clean review. UNKNOWN forbids automatic resubmission. */
public interface ReviewProvider {
    enum Result { COMMENT_CREATED, DUPLICATE, OUTCOME_UNKNOWN, IGNORED }
    Result request(ReviewRequest request);
}
