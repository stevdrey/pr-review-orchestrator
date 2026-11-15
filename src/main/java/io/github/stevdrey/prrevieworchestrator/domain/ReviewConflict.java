package io.github.stevdrey.prrevieworchestrator.domain;

/** Stable rejection codes; no provider text, SQL diagnostics or credentials. */
public final class ReviewConflict extends RuntimeException {
    public enum Code {
        ALREADY_EXISTS, NOT_FOUND, DUPLICATE_TRANSITION, STALE_VERSION, STALE_HEAD,
        ILLEGAL_TRANSITION, GUARD_REJECTED
    }

    private final Code code;

    public ReviewConflict(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
