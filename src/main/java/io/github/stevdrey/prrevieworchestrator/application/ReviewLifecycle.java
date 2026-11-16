package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewStateStore;
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
}
