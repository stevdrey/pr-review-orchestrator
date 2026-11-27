package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventQueue;

public final class DeliverDomainEvents {
    private final DomainEventQueue queue;
    private final RepositoryAuthorization authorization;
    private final DomainEventPublisher publisher;

    public DeliverDomainEvents(DomainEventQueue queue, RepositoryAuthorization authorization,
            DomainEventPublisher publisher) {
        this.queue = queue;
        this.authorization = authorization;
        this.publisher = publisher;
    }

    /** Returns false when no delivery was claimed. */
    public boolean processNext() {
        var claim = queue.claim();
        claim.ifPresent(next -> queue.deliver(next, authorization::requireAuthorized, publisher));
        return claim.isPresent();
    }
}
