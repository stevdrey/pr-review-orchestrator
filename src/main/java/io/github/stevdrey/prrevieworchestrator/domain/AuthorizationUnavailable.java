package io.github.stevdrey.prrevieworchestrator.domain;

/** Fail closed without confusing an unavailable membership authority with a confirmed denial. */
public final class AuthorizationUnavailable extends SecurityException {
    public AuthorizationUnavailable() {
        super("Repository authorization unavailable");
    }
}
