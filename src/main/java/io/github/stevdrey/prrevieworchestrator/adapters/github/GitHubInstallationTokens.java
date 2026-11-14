package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No cache, retry, redirect, caller-supplied host, or user-token fallback. */
public final class GitHubInstallationTokens {
    private static final URI API = URI.create("https://api.github.com/");
    private final RepositoryAuthorization authorization;
    private final GitHubAppJwt jwt;
    private final Clock clock;
    private final HttpClient http;
    private final URI api;
    private final JsonMapper json = JsonMapper.builder().build();

    public GitHubInstallationTokens(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock) {
        this(authorization, jwt, clock, API);
    }

    // Package-private seam for local contract tests; production callers cannot configure a token destination.
    GitHubInstallationTokens(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock, URI api) {
        this.authorization = authorization;
        this.jwt = jwt;
        this.clock = clock;
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public InstallationToken issue(RepositoryScope scope, GitHubReadOperation operation) {
        authorization.requireAuthorized(scope);
        if (operation == null) {
            throw new IllegalArgumentException("A supported GitHub read operation is required");
        }
        try {
            var request = HttpRequest.newBuilder(api.resolve("app/installations/" + scope.installationId() + "/access_tokens"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + jwt.issue())
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", "2026-03-10")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "repository_ids", List.of(scope.repositoryId()), "permissions", operation.permissions()))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException();
            }
            JsonNode body = json.readTree(response.body());
            String token = body.path("token").asString();
            Instant expiry = Instant.parse(body.path("expires_at").asString());
            if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)
                    || !expiry.isAfter(clock.instant().plusSeconds(60))) {
                throw new IllegalStateException();
            }
            JsonNode repositories = body.path("repositories");
            if (!repositories.isArray() || repositories.size() != 1
                    || repositories.get(0).path("id").asLong() != scope.repositoryId()) {
                throw new IllegalStateException();
            }
            JsonNode permissions = body.path("permissions");
            if (!permissions.isObject() || permissions.size() != operation.permissions().size()) {
                throw new IllegalStateException();
            }
            for (var permission : operation.permissions().entrySet()) {
                if (!permission.getValue().equals(permissions.path(permission.getKey()).asString())) {
                    throw new IllegalStateException();
                }
            }
            // A revocation/disable committed during the HTTP exchange must prevent use of this token.
            authorization.requireAuthorized(scope);
            return new InstallationToken(token, expiry);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ignored) {
            // Remote errors may echo credentials. Never retain their body, request or nested cause.
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("GitHub installation credential unavailable");
    }
}
