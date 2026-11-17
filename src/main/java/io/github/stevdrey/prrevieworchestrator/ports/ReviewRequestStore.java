package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import java.util.UUID;
import java.util.function.LongSupplier;

public interface ReviewRequestStore {
    enum State { RESERVED, ATTEMPTED, COMMENT_CREATED }
    record Reservation(UUID id, State state, boolean created) {}
    /** Commit a permanent tombstone before any effect; serialize per PR and enforce a lifetime cap. */
    Reservation reserve(ReviewRequest request, long actorId, int limit, Runnable authorizeAndApprove);
    /** Durably consume the send permit before the effect; retain ATTEMPTED on failure/uncertain commit. */
    void send(ReviewRequest request, UUID reservationId, Runnable authorizeAndApprove, LongSupplier effect);
}
