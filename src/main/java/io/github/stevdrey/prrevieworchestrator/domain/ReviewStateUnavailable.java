package io.github.stevdrey.prrevieworchestrator.domain;

/** Sanitized review-state persistence failure; no cause, SQL diagnostics or provider text. */
public final class ReviewStateUnavailable extends RuntimeException {
    private final boolean retryable;

    /** @param retryable false when the persisted snapshot itself is invalid and retrying cannot help */
    public ReviewStateUnavailable(boolean retryable) {
        super("REVIEW_STATE_UNAVAILABLE");
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
