package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.ACTOR_DENIED;

/** In-process, single-grant sandbox credential source. The operator owns consent and remote revocation. */
public final class GitHubSandboxUserCredentials implements CodexUserCredentials, AutoCloseable {
    private static final Duration MAX_LOCAL_USE = Duration.ofMinutes(10);
    // Two reads together use at most one second of the eight-second send transaction.
    private static final Duration SCOPE_READ_DEADLINE = Duration.ofMillis(500);
    private final RepositoryAuthorization authorization;
    private final Clock clock;
    private final GitHubSandboxDeviceOAuth.Scope scope;
    private final AtomicReference<Credential> grant = new AtomicReference<>();
    private volatile GitHubSandboxDeviceOAuth oauth;

    public GitHubSandboxUserCredentials(RepositoryAuthorization authorization, Clock clock, String clientId,
            GitHubSandboxDeviceOAuth.Scope scope) {
        this(authorization, clock, scope, new GitHubSandboxDeviceOAuth(authorization, clock, clientId, scope,
                GitHubSandboxDeviceOAuth.Access.PULL_REQUESTS_WRITE));
    }

    GitHubSandboxUserCredentials(RepositoryAuthorization authorization, Clock clock, String clientId,
            GitHubSandboxDeviceOAuth.Scope scope, URI origin) {
        this(authorization, clock, scope, new GitHubSandboxDeviceOAuth(authorization, clock, clientId, scope,
                GitHubSandboxDeviceOAuth.Access.PULL_REQUESTS_WRITE, origin, origin));
    }

    private GitHubSandboxUserCredentials(RepositoryAuthorization authorization, Clock clock,
            GitHubSandboxDeviceOAuth.Scope scope, GitHubSandboxDeviceOAuth oauth) {
        this.authorization = authorization;
        this.clock = clock;
        this.scope = scope;
        this.oauth = oauth;
    }

    public synchronized GitHubSandboxDeviceOAuth.Challenge start(UUID operatorSession) {
        if (oauth == null) throw new ReviewRequestFailure(ACTOR_DENIED);
        return oauth.start(operatorSession);
    }

    public synchronized GitHubSandboxDeviceOAuth.Progress poll(UUID operatorSession) {
        if (oauth == null) throw new ReviewRequestFailure(ACTOR_DENIED);
        return oauth.poll(operatorSession, (token, expiresAt) -> {
            var localExpiry = clock.instant().plus(MAX_LOCAL_USE);
            grant.set(new Credential(scope.pr().scope(), scope.actorId(), scope.repository(),
                    token, expiresAt.isBefore(localExpiry) ? expiresAt : localExpiry));
        });
    }

    @Override public Credential current(RepositoryScope repository) {
        if (!scope.pr().scope().equals(repository)) throw new ReviewRequestFailure(ACTOR_DENIED);
        var credential = grant.get();
        if (credential == null) throw new ReviewRequestFailure(ACTOR_DENIED);
        try {
            requireUsable(credential);
            authorization.requireAuthorized(repository);
            var deviceGrant = oauth;
            if (deviceGrant == null) throw new ReviewRequestFailure(ACTOR_DENIED);
            deviceGrant.verifyInstallationAndRepository(credential.token(), SCOPE_READ_DEADLINE);
            authorization.requireAuthorized(repository);
            requireUsable(credential);
            return credential;
        } catch (IllegalStateException invalidGrant) {
            grant.compareAndSet(credential, null);
            throw new ReviewRequestFailure(ACTOR_DENIED);
        } catch (RuntimeException denied) {
            grant.compareAndSet(credential, null);
            throw denied;
        }
    }

    private void requireUsable(Credential credential) {
        if (grant.get() != credential || !credential.expiresAt().isAfter(clock.instant().plusSeconds(30))) {
            throw new ReviewRequestFailure(ACTOR_DENIED);
        }
    }

    @Override public synchronized void close() {
        grant.set(null);
        oauth = null;
    }
}
