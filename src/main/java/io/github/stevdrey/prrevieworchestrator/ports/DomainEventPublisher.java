package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import java.util.UUID;

@FunctionalInterface
public interface DomainEventPublisher {
    /** {@code MISCONFIGURED} is retryable, but the cause is local callback or key configuration. */
    enum Outcome { ACKNOWLEDGED, RETRY, REJECTED, MISCONFIGURED }

    /**
     * Publish within the worker's deadline. Use (subscriptionId, eventId) as the same idempotency
     * key across attempts, including after an unacknowledged send. The destination must match its
     * registered fingerprint. The result represents transport receipt only.
     */
    Outcome publish(UUID subscriptionId, String callbackFingerprint, PrDomainEvent event);
}
