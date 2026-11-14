package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.VerifiedDelivery;
import java.util.UUID;

public interface DeliveryInbox {
    /** Returns only after durable commit. Reused IDs with different contents must conflict. */
    Receipt accept(VerifiedDelivery delivery);

    record Receipt(boolean duplicate, UUID correlationId) {}

    final class Conflict extends RuntimeException {
        public Conflict() {
            super("Delivery identity conflicts with stored evidence");
        }
    }
}
