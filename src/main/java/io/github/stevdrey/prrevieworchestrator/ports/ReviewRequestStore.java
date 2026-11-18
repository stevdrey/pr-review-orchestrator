package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import java.util.UUID;
import java.util.function.Supplier;

public interface ReviewRequestStore {
    /** NOT_SENT is terminal and provably effect-free; it still consumes the key and lifetime slot. */
    enum State { RESERVED, ATTEMPTED, NOT_SENT, COMMENT_CREATED, COMMENT_CREATED_HEAD_UNCONFIRMED }
    record Reservation(UUID id, State state, boolean created) {}
    /** A validated comment receipt; headConfirmed is false when the post-write read did not confirm the SHA. */
    record Receipt(long commentId, boolean headConfirmed) {}
    /** Commit a permanent tombstone before any effect; serialize per PR and enforce a lifetime cap. */
    Reservation reserve(ReviewRequest request, long actorId, int limit, Runnable authorize, Runnable approve);
    /**
     * Durably consume the send permit before the effect; retain ATTEMPTED on failure/uncertain commit.
     * The effect must throw ReviewRequestFailure(OUTCOME_UNKNOWN) once a remote write may have happened;
     * any other failure asserts that no write was attempted and records the terminal NOT_SENT state.
     * Failures after the effect returned surface as OUTCOME_UNKNOWN.
     */
    void send(ReviewRequest request, UUID reservationId, Runnable authorizeAndApprove, Supplier<Receipt> effect);
}
