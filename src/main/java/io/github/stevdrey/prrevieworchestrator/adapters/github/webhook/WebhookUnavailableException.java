package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

/** Transient ingress infrastructure (mounted secret or crypto provider) is unavailable; maps to 503. */
final class WebhookUnavailableException extends IllegalStateException {
    WebhookUnavailableException(String message) {
        super(message);
    }
}
