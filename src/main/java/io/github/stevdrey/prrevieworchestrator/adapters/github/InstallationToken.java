package io.github.stevdrey.prrevieworchestrator.adapters.github;

import java.time.Instant;

/** Ephemeral credential. Never persist, serialize, log, or send to an AI adapter. */
public final class InstallationToken {
    private final String token;
    private final Instant expiresAt;

    InstallationToken(String token, Instant expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public String authorizationHeader() {
        return "Bearer " + token;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "InstallationToken[REDACTED]";
    }
}
