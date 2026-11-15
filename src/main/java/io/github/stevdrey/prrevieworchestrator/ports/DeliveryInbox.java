package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.VerifiedDelivery;
import java.util.UUID;

public interface DeliveryInbox {
    /**
     * Runs {@code precondition} inside the same transaction as the write, then returns only after durable
     * commit. A failing precondition aborts without acceptance. Reused IDs with different contents must conflict.
     */
    Receipt accept(VerifiedDelivery delivery, Runnable precondition);

    /** Fails when the backing store lacks capabilities this inbox depends on. */
    default void requireCapabilities() {}

    record Receipt(boolean duplicate, UUID correlationId) {}

    final class Conflict extends RuntimeException {
        public Conflict() {
            super("Delivery identity conflicts with stored evidence");
        }
    }
}
