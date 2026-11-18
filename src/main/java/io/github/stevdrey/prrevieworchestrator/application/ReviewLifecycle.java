package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewStateStore;
import java.util.Objects;
import java.util.Optional;

/** Internal use cases only. Callers must correlate authoritative head, normalized reviews and policy gates. */
public final class ReviewLifecycle {
    private final ReviewStateStore store;
    private final RepositoryAuthorization authorization;

    public ReviewLifecycle(ReviewStateStore store, RepositoryAuthorization authorization) {
        this.store = java.util.Objects.requireNonNull(store);
        this.authorization = java.util.Objects.requireNonNull(authorization);
    }

    public PullRequestReview create(PullRequestId pr, String headSha) {
        return store.create(pr, headSha, () -> authorization.requireAuthorized(pr.scope()));
    }

    public Optional<PullRequestReview> find(PullRequestId pr) {
        return store.find(pr, () -> authorization.requireAuthorized(pr.scope()));
    }

    public PullRequestReview apply(PullRequestId pr, ReviewTransition transition) {
        return store.apply(pr, transition, () -> authorization.requireAuthorized(pr.scope()));
    }

    /** Capture the local version before the authoritative PR and CI reads. */
    public PullRequestReview reconcileCi(long expectedVersion, CiEvidence evidence) {
        return store.reconcileCi(expectedVersion, evidence,
                () -> authorization.requireAuthorized(evidence.pr().scope()));
    }

    /** A verified event is a wake-up signal. Its conclusion and PR associations carry no authority. */
    public Optional<PullRequestReview> observeCi(Event event, long expectedVersion, CiEvidence evidence) {
        var pr = evidence.pr();
        if (!("check_run".equals(event.eventType()) || "workflow_run".equals(event.eventType()))
                || !pr.scope().equals(event.scope())
                || event.prNumber() != null && !Objects.equals(event.prNumber(), pr.number())) {
            throw new IllegalArgumentException("Invalid CI observation");
        }
        if (!evidence.headSha().equals(event.observedHeadSha())) {
            authorization.requireAuthorized(pr.scope());
            return Optional.empty();
        }
        return Optional.of(reconcileCi(expectedVersion, evidence));
    }
}
