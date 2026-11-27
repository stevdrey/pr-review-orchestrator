package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface DomainEventQueue {
    record Claim(UUID subscriptionId, UUID eventId, int attempt) {}

    Optional<Claim> claim();

    /** Fence the claim and hold live subscription and repository authorization through publication. */
    void deliver(Claim claim, Consumer<RepositoryScope> authorize, DomainEventPublisher publisher);
}
