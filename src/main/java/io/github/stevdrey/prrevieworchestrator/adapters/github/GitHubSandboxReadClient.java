package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler.Policy;
import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Head;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.ErrorCode;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Failure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, redirect-free reads shared by the sandbox routes. */
final class GitHubSandboxReadClient {
    static final URI API = URI.create("https://api.github.com/");
    static final Duration API_DEADLINE = Duration.ofSeconds(3);
    private static final Duration OBSERVATION_DEADLINE = Duration.ofSeconds(20);
    private static final int BODY_LIMIT = 2 * 1024 * 1024;
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final URI api;
    private final GitHubInstallationTokens tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(API_DEADLINE)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    GitHubSandboxReadClient(URI api, GitHubInstallationTokens tokens) {
        this.api = api;
        this.tokens = tokens;
    }

    long observationDeadline() {
        return System.nanoTime() + OBSERVATION_DEADLINE.toNanos();
    }

    String pullPath(Policy policy) {
        return "repos/" + policy.repository() + "/pulls/" + policy.pr().number();
    }

    boolean currentPr(Policy policy, InstallationToken token, long deadline) {
        return readHead(policy, token, deadline).filter(head ->
                policy.head().equals(head.sha()) && head.open() && !head.draft()).isPresent();
    }

    Optional<Head> readHead(Policy policy, InstallationToken token, long deadline) {
        var pr = get(policy, pullPath(policy), token, deadline);
        long repositoryId = policy.pr().scope().repositoryId();
        String sha = pr.path("head").path("sha").asString();
        String state = pr.path("state").asString();
        if (pr.path("number").asInt() != policy.pr().number()
                || pr.path("base").path("repo").path("id").asLong() != repositoryId
                || pr.path("head").path("repo").path("id").asLong() != repositoryId
                || !CommitSha.isValid(sha) || !("open".equals(state) || "closed".equals(state))
                || !pr.path("draft").isBoolean()) {
            return Optional.empty();
        }
        return Optional.of(new Head(policy.pr(), sha, "open".equals(state), pr.path("draft").asBoolean()));
    }

    JsonNode get(Policy policy, String path, InstallationToken token, long deadline) {
        return read(policy, path, token, deadline, HttpRequest.BodyPublishers.noBody(), "GET");
    }

    JsonNode graphQl(Policy policy, String query, InstallationToken token, long deadline) {
        return read(policy, "graphql", token, deadline, HttpRequest.BodyPublishers.ofString(query), "POST");
    }

    private JsonNode read(Policy policy, String path, InstallationToken token, long deadline,
            HttpRequest.BodyPublisher body, String method) {
        try {
            tokens.requireUsable(policy.readScope(), token);
        } catch (IllegalStateException expired) {
            throw unavailable();
        }
        long remaining = Math.min(deadline - System.nanoTime(), API_DEADLINE.toNanos());
        if (remaining <= 0) throw unavailable();

        var request = HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofNanos(remaining))
                .header("Authorization", token.authorizationHeader())
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .method(method, body).build();
        var exchange = http.sendAsync(request, info -> info.statusCode() == 200
                ? new GitHubInstallationTokens.BoundedBody(BODY_LIMIT)
                : HttpResponse.BodySubscribers.replacing(""));
        try {
            var response = exchange.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || response.headers().firstValue("Link").isPresent()) throw unavailable();
            return JSON.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            exchange.cancel(true);
        }
    }

    static Failure unavailable() {
        return new Failure(ErrorCode.REMOTE_UNAVAILABLE, true);
    }
}
