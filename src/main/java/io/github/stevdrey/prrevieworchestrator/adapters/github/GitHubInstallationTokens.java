package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No cache, retry, redirect, caller-supplied host, or user-token fallback. */
public final class GitHubInstallationTokens {
    private static final URI API = URI.create("https://api.github.com/");
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    static final int MAX_BODY_BYTES = 64 * 1024;
    private final RepositoryAuthorization authorization;
    private final GitHubAppJwt jwt;
    private final Clock clock;
    private final HttpClient http;
    private final URI api;
    private final Duration deadline;
    private final JsonMapper json = JsonMapper.builder().build();

    public GitHubInstallationTokens(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock) {
        this(authorization, jwt, clock, API, DEADLINE);
    }

    // Package-private seams for local contract tests; production callers cannot configure a token destination.
    GitHubInstallationTokens(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock, URI api) {
        this(authorization, jwt, clock, api, DEADLINE);
    }

    GitHubInstallationTokens(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock, URI api,
            Duration deadline) {
        this.authorization = authorization;
        this.jwt = jwt;
        this.clock = clock;
        this.api = api;
        this.deadline = deadline;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public InstallationToken issue(RepositoryScope scope, GitHubReadOperation operation) {
        authorization.requireAuthorized(scope);
        if (operation == null) {
            throw new IllegalArgumentException("A supported GitHub read operation is required");
        }
        String token = null;
        try {
            var request = HttpRequest.newBuilder(api.resolve("app/installations/" + scope.installationId() + "/access_tokens"))
                    .timeout(deadline)
                    .header("Authorization", "Bearer " + jwt.issue())
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", "2026-03-10")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "repository_ids", List.of(scope.repositoryId()), "permissions", operation.permissions()))))
                    .build();
            // One deadline covers headers and body; only a successful response body is buffered, and only up to a cap.
            var response = within(http.sendAsync(request, info -> info.statusCode() == 201
                    ? new BoundedBody() : HttpResponse.BodySubscribers.replacing("")));
            if (response.statusCode() != 201) {
                throw new IllegalStateException();
            }
            JsonNode body = json.readTree(response.body());
            JsonNode tokenNode = body.path("token");
            if (!tokenNode.isString() || tokenNode.asString().isBlank()
                    || tokenNode.asString().chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalStateException();
            }
            // From here on a live credential exists remotely; every failure below revokes it.
            token = tokenNode.asString();
            JsonNode expiresAt = body.path("expires_at");
            if (!expiresAt.isString()) {
                throw new IllegalStateException();
            }
            Instant expiry = Instant.parse(expiresAt.asString());
            if (!expiry.isAfter(clock.instant().plusSeconds(60))) {
                throw new IllegalStateException();
            }
            JsonNode repositories = body.path("repositories");
            if (!repositories.isArray() || repositories.size() != 1
                    || !repositories.get(0).path("id").isIntegralNumber()
                    || repositories.get(0).path("id").asLong() != scope.repositoryId()) {
                throw new IllegalStateException();
            }
            JsonNode permissions = body.path("permissions");
            if (!permissions.isObject() || permissions.size() != operation.permissions().size()) {
                throw new IllegalStateException();
            }
            for (var permission : operation.permissions().entrySet()) {
                JsonNode granted = permissions.path(permission.getKey());
                if (!granted.isString() || !permission.getValue().equals(granted.asString())) {
                    throw new IllegalStateException();
                }
            }
            // A revocation/disable committed during the HTTP exchange must prevent use of this token.
            authorization.requireAuthorized(scope);
            return new InstallationToken(token, expiry);
        } catch (SecurityException denied) {
            // Keep authorization denials distinct from transient unavailability; the gate's exception is sanitized.
            revoke(token);
            throw denied;
        } catch (InterruptedException ignored) {
            revoke(token);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ignored) {
            // Remote errors may echo credentials. Never retain their body, request or nested cause.
            revoke(token);
            throw unavailable();
        }
    }

    /** Best-effort: a rejected token must not stay usable remotely. All outcomes are ignored. */
    private void revoke(String token) {
        if (token == null) {
            return;
        }
        try {
            var request = HttpRequest.newBuilder(api.resolve("installation/token"))
                    .timeout(deadline)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2026-03-10")
                    .DELETE()
                    .build();
            within(http.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Never surface revocation diagnostics; the caller already fails closed.
        }
    }

    private <T> HttpResponse<T> within(CompletableFuture<HttpResponse<T>> exchange) throws Exception {
        try {
            return exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            exchange.cancel(true);
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("GitHub installation credential unavailable");
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException());
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.write(bytes, 0, bytes.length);
            }
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
