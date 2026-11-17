package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.time.Instant;
import java.util.Objects;

/** Only explicitly consented GitHub App USER tokens. No PAT or installation-token fallback.
 * The credential source owns OAuth, encrypted storage, refresh, revocation and App-installation
 * binding. It must revalidate consent and exact scope on every call; no implementation is supplied.
 */
public interface CodexUserCredentials {
    Credential current(RepositoryScope scope);
    final class Credential {
        private final RepositoryScope scope;
        private final long actorId;
        private final String repository;
        private final String token;
        private final Instant expiresAt;
        public Credential(RepositoryScope scope, long actorId, String repository, String token, Instant expiresAt) {
            this.scope = Objects.requireNonNull(scope);
            this.expiresAt = Objects.requireNonNull(expiresAt);
            if (actorId <= 0 || repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                    || repository.contains("..") || token == null || token.isBlank()
                    || !token.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid user credential");
            this.actorId = actorId;
            this.repository = repository;
            this.token = token;
        }
        public RepositoryScope scope() { return scope; }
        public long actorId() { return actorId; }
        public String repository() { return repository; }
        public String token() { return token; }
        public Instant expiresAt() { return expiresAt; }
        @Override public String toString() { return "CodexUserCredential[REDACTED]"; }
    }
}
