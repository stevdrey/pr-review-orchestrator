package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.VerifiedDelivery;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;

/** Every delivery, including retries, must pass current repository authorization. */
public final class AcceptDelivery {
    private final RepositoryAuthorization authorization;
    private final DeliveryInbox inbox;

    public AcceptDelivery(RepositoryAuthorization authorization, DeliveryInbox inbox) {
        this.authorization = authorization;
        this.inbox = inbox;
    }

    public void requireCapabilities() {
        inbox.requireCapabilities();
    }

    public DeliveryInbox.Receipt accept(VerifiedDelivery delivery) {
        // Authorize inside the inbox transaction so a concurrent revocation cannot race the commit.
        return inbox.accept(delivery, () -> authorization.requireAuthorized(delivery.scope()));
    }
}
