package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** One explicit OAuth device grant. The public probe API returns scope evidence only. */
public final class GitHubSandboxDeviceOAuth {
    private static final URI GITHUB = URI.create("https://github.com/");
    private static final URI API = URI.create("https://api.github.com/");
    private static final URI VERIFICATION = URI.create("https://github.com/login/device");
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private static final Set<String> READ_PERMISSIONS = Set.of("metadata", "pull_requests", "actions", "checks");

    public enum Access { READ_ONLY, PULL_REQUESTS_WRITE }

    public record Scope(PullRequestId pr, String repository, String head, long actorId) {
        public Scope {
            CommitSha.require(head);
            if (pr == null || actorId <= 0 || repository == null
                    || !repository.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")
                    || repository.endsWith("/.") || repository.endsWith("/..")) {
                throw new IllegalArgumentException("An exact sandbox PR and actor are required");
            }
        }
    }

    public record Challenge(String userCode, URI verificationUrl, Instant expiresAt) {
        @Override public String toString() { return "GitHub sandbox OAuth challenge [REDACTED]"; }
    }

    public record Result(long actorId, PullRequestId pr, String head) {}

    public record Progress(Instant nextPoll, Result verified) {}

    private record Attempt(UUID operatorSession, String deviceCode, Instant expiresAt, long delay, Instant nextPoll) {
        @Override public String toString() { return "GitHub sandbox OAuth attempt [REDACTED]"; }
    }

    private final RepositoryAuthorization authorization;
    private final Clock clock;
    private final String clientId;
    private final Scope scope;
    private final Access access;
    private final URI github;
    private final URI api;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(DEADLINE).build();
    private Attempt attempt;
    private boolean started;

    public GitHubSandboxDeviceOAuth(RepositoryAuthorization authorization, Clock clock, String clientId, Scope scope) {
        this(authorization, clock, clientId, scope, Access.READ_ONLY);
    }

    public GitHubSandboxDeviceOAuth(RepositoryAuthorization authorization, Clock clock, String clientId, Scope scope,
            Access access) {
        this(authorization, clock, clientId, scope, access, GITHUB, API);
    }

    GitHubSandboxDeviceOAuth(RepositoryAuthorization authorization, Clock clock, String clientId, Scope scope,
            Access access, URI github, URI api) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9.]{8,80}")
                || access == null
                || !(GITHUB.equals(github) && API.equals(api) || loopback(github) && loopback(api))) {
            throw new IllegalArgumentException("Invalid GitHub OAuth configuration");
        }
        this.authorization = authorization;
        this.clock = clock;
        this.clientId = clientId;
        this.scope = scope;
        this.access = access;
        this.github = github;
        this.api = api;
    }

    public synchronized Challenge start(UUID operatorSession) {
        require(!started && operatorSession != null);
        authorization.requireAuthorized(scope.pr().scope());
        started = true;
        var reply = exchange(form(github.resolve("login/device/code"), Map.of("client_id", clientId)));
        String userCode = reply.path("user_code").asString();
        String code = reply.path("device_code").asString();
        long expires = number(reply.path("expires_in"));
        long delay = number(reply.path("interval"));
        require(userCode.matches("[A-Z0-9]{4}-[A-Z0-9]{4}") && code.matches("[A-Za-z0-9_.-]{16,512}")
                && expires <= 900 && delay <= 60 && VERIFICATION.toString().equals(reply.path("verification_uri").asString()));
        var now = clock.instant();
        attempt = new Attempt(operatorSession, code, now.plusSeconds(expires), delay, now.plusSeconds(delay));
        return new Challenge(userCode, VERIFICATION, attempt.expiresAt());
    }

    public synchronized Progress poll(UUID operatorSession) {
        return poll(operatorSession, (token, expiry) -> {});
    }

    synchronized Progress poll(UUID operatorSession, BiConsumer<String, Instant> verifiedGrant) {
        var current = attempt;
        require(current != null && current.operatorSession().equals(operatorSession));
        if (!clock.instant().isBefore(current.expiresAt())) {
            attempt = null;
            throw unverified();
        }
        authorization.requireAuthorized(scope.pr().scope());
        if (clock.instant().isBefore(current.nextPoll())) return new Progress(current.nextPoll(), null);
        attempt = null;
        var requestedAt = clock.instant();
        var reply = exchange(form(github.resolve("login/oauth/access_token"), Map.of(
                "client_id", clientId, "device_code", current.deviceCode(),
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "repository_id", Long.toString(scope.pr().scope().repositoryId()))));
        String error = reply.path("error").asString();
        if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
            long delay = current.delay();
            if ("slow_down".equals(error)) {
                delay = Math.max(delay + 5, reply.path("interval").asLong(0));
            }
            require(delay <= 900);
            var next = clock.instant().plusSeconds(delay);
            require(next.isBefore(current.expiresAt()));
            attempt = new Attempt(operatorSession, current.deviceCode(), current.expiresAt(), delay, next);
            return new Progress(next, null);
        }
        require(!reply.has("error") && clock.instant().isBefore(current.expiresAt()));
        String token = reply.path("access_token").asString();
        long expires = number(reply.path("expires_in"));
        require(token.matches("ghu_[A-Za-z0-9_]{12,512}") && "bearer".equalsIgnoreCase(reply.path("token_type").asString())
                && reply.path("scope").isString() && reply.path("scope").asString().isEmpty()
                && expires <= 28_800);
        var result = verify(token);
        var expiresAt = requestedAt.plusSeconds(expires);
        require(expiresAt.isAfter(clock.instant()));
        verifiedGrant.accept(token, expiresAt);
        return new Progress(null, result);
    }

    private Result verify(String token) {
        var pr = scope.pr();
        var identity = get("user", token);
        require(number(identity.path("id")) == scope.actorId() && "User".equals(identity.path("type").asString()));
        verifyInstallationAndRepository(token, DEADLINE);
        var remote = get("repos/" + scope.repository() + "/pulls/" + pr.number(), token);
        require(number(remote.path("number")) == pr.number() && "open".equals(remote.path("state").asString())
                && remote.path("draft").isBoolean() && !remote.path("draft").asBoolean()
                && number(remote.path("base").path("repo").path("id")) == pr.scope().repositoryId()
                && number(remote.path("head").path("repo").path("id")) == pr.scope().repositoryId()
                && scope.head().equals(remote.path("head").path("sha").asString()));
        authorization.requireAuthorized(pr.scope());
        return new Result(scope.actorId(), pr, scope.head());
    }

    void verifyInstallationAndRepository(String token, Duration deadline) {
        var pr = scope.pr();
        var installation = only(get("user/installations?per_page=2", token, deadline), "installations");
        require(number(installation.path("id")) == pr.scope().installationId()
                && "selected".equals(installation.path("repository_selection").asString())
                && installation.path("suspended_at").isNull());
        var permissions = installation.path("permissions");
        require(permissions.isObject() && "read".equals(permissions.path("metadata").asString()));
        if (access == Access.PULL_REQUESTS_WRITE) {
            require(permissions.size() == 2 && "write".equals(permissions.path("pull_requests").asString()));
        } else {
            require("read".equals(permissions.path("pull_requests").asString()));
            var names = permissions.propertyNames().iterator();
            while (names.hasNext()) {
                String name = names.next();
                require(READ_PERMISSIONS.contains(name) && "read".equals(permissions.path(name).asString()));
            }
        }
        var repository = only(get("user/installations/" + pr.scope().installationId() + "/repositories?per_page=2", token, deadline),
                "repositories");
        require(number(repository.path("id")) == pr.scope().repositoryId()
                && scope.repository().equalsIgnoreCase(repository.path("full_name").asString()));
    }

    private JsonNode only(JsonNode body, String property) {
        var list = body.path(property);
        require(number(body.path("total_count")) == 1 && list.isArray() && list.size() == 1);
        return list.get(0);
    }

    private JsonNode get(String path, String token) {
        return get(path, token, DEADLINE);
    }

    private JsonNode get(String path, String token, Duration deadline) {
        return exchange(HttpRequest.newBuilder(api.resolve(path)).timeout(deadline)
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("Authorization", "Bearer " + token).GET().build());
    }

    private HttpRequest form(URI url, Map<String, String> parameters) {
        String form = parameters.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(url).timeout(DEADLINE).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    }

    private JsonNode exchange(HttpRequest request) {
        var task = http.sendAsync(request, info -> info.statusCode() == 200
                ? new GitHubInstallationTokens.BoundedBody() : HttpResponse.BodySubscribers.replacing(""));
        try {
            var reply = task.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
            require(reply.statusCode() == 200 && reply.headers().firstValue("Link").isEmpty());
            var body = GitHubSandboxReadClient.JSON.readTree(reply.body());
            require(body != null && body.isObject());
            return body;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unverified();
        } catch (Exception ignored) {
            throw unverified();
        } finally {
            task.cancel(true);
        }
    }

    private static long number(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0);
        return node.asLong();
    }

    private static boolean loopback(URI origin) {
        return "http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost())
                && "/".equals(origin.getPath()) && origin.getUserInfo() == null
                && origin.getQuery() == null && origin.getFragment() == null;
    }

    private static void require(boolean valid) {
        if (!valid) throw unverified();
    }

    private static IllegalStateException unverified() {
        return new IllegalStateException("GitHub sandbox OAuth could not be verified");
    }
}
