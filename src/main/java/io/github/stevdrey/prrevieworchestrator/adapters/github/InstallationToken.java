package io.github.stevdrey.prrevieworchestrator.adapters.github;

import java.time.Instant;

/** Ephemeral credential. Never persist, serialize, log, or send to an AI adapter. */
public final class InstallationToken {
    private final String token;
    private final Instant expiresAt;
    private final String repository;

    InstallationToken(String token, Instant expiresAt, String repository) {
        this.token = token;
        this.expiresAt = expiresAt;
        this.repository = repository;
    }

    public String authorizationHeader() {
        return "Bearer " + token;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    String repository() {
        return repository;
    }

    @Override
    public String toString() {
        return "InstallationToken[REDACTED]";
    }
}
