package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;

/** Request acceptance is not a completed or clean review. UNKNOWN forbids automatic resubmission.
 * PENDING: reserved, no send attempted yet (in flight or crashed before the permit). NOT_SENT: terminal,
 * provably no remote write. Neither permits resubmission of the same key.
 */
public interface ReviewProvider {
    enum Result { COMMENT_CREATED, DUPLICATE, PENDING, NOT_SENT, OUTCOME_UNKNOWN, IGNORED }
    Result request(ReviewRequest request);
}
