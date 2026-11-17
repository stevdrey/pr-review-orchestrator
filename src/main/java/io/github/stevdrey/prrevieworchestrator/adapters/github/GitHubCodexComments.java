package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials.Credential;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.*;

/** Fixed user-comment trigger only, no custom prompt, redirects, retries, or token persistence.
 * HTTP 201 proves comment creation, never Codex acceptance, billing, completion or reviewed SHA.
 */
public final class GitHubCodexComments {
    private static final Duration DEADLINE = Duration.ofSeconds(1);
    private final CodexUserCredentials credentials;
    private final Clock clock;
    private final URI api;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public GitHubCodexComments(CodexUserCredentials credentials, Clock clock) {
        this(credentials, clock, URI.create("https://api.github.com/"));
    }
    // Loopback test seam only. Production callers cannot supply an arbitrary credential destination.
    GitHubCodexComments(CodexUserCredentials credentials, Clock clock, URI api) {
        this.credentials = java.util.Objects.requireNonNull(credentials);
        this.clock = java.util.Objects.requireNonNull(clock);
        if (!api.equals(URI.create("https://api.github.com/"))
                && !("http".equals(api.getScheme()) && "127.0.0.1".equals(api.getHost())
                && "/".equals(api.getPath()) && api.getUserInfo() == null && api.getQuery() == null
                && api.getFragment() == null)) throw new IllegalArgumentException("Invalid GitHub API origin");
        this.api = api;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(DEADLINE).build();
    }

    public long create(ReviewRequest request, long actorId, Runnable finalGuards) {
        boolean posting = false;
        try {
            Credential credential = credentials.current(request.pr().scope());
            if (credential == null || !request.pr().scope().equals(credential.scope())
                    || credential.actorId() != actorId || !credential.expiresAt().isAfter(clock.instant().plusSeconds(30))) {
                throw new ReviewRequestFailure(ACTOR_DENIED);
            }
            JsonNode user = exchange(credential, "user", false);
            if (!user.path("id").isIntegralNumber() || user.path("id").asLong() != actorId
                    || !"User".equals(user.path("type").asString())) throw new ReviewRequestFailure(ACTOR_DENIED);
            String repository = "repos/" + credential.repository();
            String prPath = repository + "/pulls/" + request.pr().number();
            requireReady(exchange(credential, prPath, false), request);
            finalGuards.run();
            if (!credential.expiresAt().isAfter(clock.instant().plusSeconds(30))) throw new ReviewRequestFailure(ACTOR_DENIED);
            posting = true;
            JsonNode comment = exchange(credential, repository + "/issues/" + request.pr().number() + "/comments", true);
            if (!comment.path("id").isIntegralNumber() || comment.path("id").asLong() <= 0
                    || !comment.path("user").path("id").isIntegralNumber()
                    || comment.path("user").path("id").asLong() != actorId
                    || !"User".equals(comment.path("user").path("type").asString())
                    || !"@codex review".equals(comment.path("body").asString())) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            // GitHub has no compare-and-swap comment API. Detect observed head changes, never claim
            // that the resulting review targets our SHA. Subsequent review evidence needs its own SHA.
            requireReady(exchange(credential, prPath, false), request);
            return comment.path("id").asLong();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new ReviewRequestFailure(posting ? OUTCOME_UNKNOWN : REMOTE_UNAVAILABLE);
        } catch (Exception failure) {
            // Even a known failure is copied: do not retain remote causes or suppressed errors.
            if (posting) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            if (failure instanceof AuthorizationDenied) throw new AuthorizationDenied();
            if (failure instanceof AuthorizationUnavailable) throw new AuthorizationUnavailable();
            if (failure instanceof ReviewRequestFailure known) throw new ReviewRequestFailure(known.code());
            throw new ReviewRequestFailure(REMOTE_UNAVAILABLE);
        }
    }

    private void requireReady(JsonNode pr, ReviewRequest request) {
        if (!pr.path("base").path("repo").path("id").isIntegralNumber()
                || pr.path("base").path("repo").path("id").asLong() != request.pr().scope().repositoryId()
                || !pr.path("number").isIntegralNumber() || pr.path("number").asInt() != request.pr().number()) {
            throw new ReviewRequestFailure(ACTOR_DENIED);
        }
        if (!"open".equals(pr.path("state").asString()) || !pr.path("draft").isBoolean()
                || pr.path("draft").asBoolean()) throw new ReviewRequestFailure(PR_NOT_READY);
        if (!request.headSha().equals(pr.path("head").path("sha").asString())) throw new ReviewRequestFailure(STALE_HEAD);
    }

    private JsonNode exchange(Credential credential, String path, boolean post) throws Exception {
        var builder = HttpRequest.newBuilder(api.resolve(path)).timeout(DEADLINE)
                .header("Authorization", "Bearer " + credential.token())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10");
        if (post) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"body\":\"@codex review\"}"));
        var future = http.sendAsync(builder.build(), info -> info.statusCode() == (post ? 201 : 200)
                ? HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 262144).apply(info)
                : HttpResponse.BodySubscribers.replacing(""));
        try {
            var response = future.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != (post ? 201 : 200)) {
                throw new ReviewRequestFailure(post ? OUTCOME_UNKNOWN : REMOTE_UNAVAILABLE);
            }
            return json.readTree(response.body());
        } finally { future.cancel(true); }
    }
}
