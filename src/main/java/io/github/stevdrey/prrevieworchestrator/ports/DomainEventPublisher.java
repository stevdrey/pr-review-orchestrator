package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import java.util.UUID;

@FunctionalInterface
public interface DomainEventPublisher {
    enum Outcome { ACKNOWLEDGED, RETRY, REJECTED }

    /**
     * Publish within the worker's deadline. Use (subscriptionId, eventId) as the same idempotency
     * key across attempts, including after an unacknowledged send. The destination must match its
     * registered fingerprint. The result represents transport receipt only.
     */
    Outcome publish(UUID subscriptionId, String callbackFingerprint, PrDomainEvent event);
}
