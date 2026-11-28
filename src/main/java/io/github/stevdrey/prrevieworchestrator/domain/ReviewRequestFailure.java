package io.github.stevdrey.prrevieworchestrator.domain;

/** Stable actionable diagnostics only; never retain remote bodies, credentials or nested causes. */
public final class ReviewRequestFailure extends RuntimeException {
    public enum Code {
        DISABLED, UNSUPPORTED_TRIGGER, CAPABILITY_UNVERIFIED, ACTOR_DENIED, STALE_HEAD,
        PR_NOT_READY, LIMIT_REACHED, CI_NOT_PASSED, HUMAN_INTERVENTION_REQUIRED, POLICY_DENIED,
        REMOTE_UNAVAILABLE, OUTCOME_UNKNOWN, STORE_UNAVAILABLE, QUIET_HOURS
    }
    private final Code code;
    public ReviewRequestFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
    public static RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof ReviewRequestFailure known) return new ReviewRequestFailure(known.code());
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        return new ReviewRequestFailure(Code.STORE_UNAVAILABLE);
    }
}
