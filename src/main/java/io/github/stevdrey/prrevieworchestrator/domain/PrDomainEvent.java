package io.github.stevdrey.prrevieworchestrator.domain;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An immutable, metadata-only observation about a committed PR transition. */
public record PrDomainEvent(UUID eventId, PullRequestId pr, String headSha, long stateVersion,
        UUID traceId, Instant occurredAt, Action action, State state, Ci ci) {
    public static final int SCHEMA_VERSION = 1;

    public PrDomainEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(pr);
        CommitSha.require(headSha);
        Objects.requireNonNull(traceId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(action);
        Objects.requireNonNull(state);
        if (stateVersion <= 0 || (action == Action.RECONCILE_CI) != (ci != null)) {
            throw new IllegalArgumentException("Invalid PR transition event");
        }
    }

    public String name() {
        return switch (action) {
            case BASELINE_CLEAN, DEEP_CLEAN -> "pr.review.completed";
            case BLOCKING_FINDINGS -> "pr.feedback.detected";
            case RECONCILE_CI -> ci == Ci.FAILED ? "pr.ci.failed" : "pr.review.transitioned";
            case MARK_READY_FOR_HUMAN, RESUME -> state == State.READY_FOR_HUMAN
                    ? "pr.ready.for.validation" : "pr.review.transitioned";
            case STOP_ITERATION_LIMIT -> "pr.review.limit.reached";
            case STOP_NO_PROGRESS -> "pr.human.approval.required";
            default -> "pr.review.transitioned";
        };
    }
}
