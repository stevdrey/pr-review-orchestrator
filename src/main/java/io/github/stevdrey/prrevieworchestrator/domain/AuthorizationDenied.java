package io.github.stevdrey.prrevieworchestrator.domain;

/** A confirmed repository-authorization denial; unrelated SecurityExceptions are not denials. */
public final class AuthorizationDenied extends SecurityException {
    public AuthorizationDenied() {
        super("Repository authorization denied");
    }
}
