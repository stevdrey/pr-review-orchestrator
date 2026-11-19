package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticePublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class GitHubAuthorNotices implements AuthorNoticePublisher {
    static final String MARKER = "<!-- pr-review-orchestrator:author-notice:v1 -->\n";
    private static final Pattern USER_LOGIN = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?");
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final GitHubInstallationTokens tokens;
    private final Clock clock;
    private final String botLogin;
    private final URI api;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(DEADLINE).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public GitHubAuthorNotices(GitHubInstallationTokens tokens, Clock clock, String botLogin) {
        this(tokens, clock, botLogin, URI.create("https://api.github.com/"));
    }

    GitHubAuthorNotices(GitHubInstallationTokens tokens, Clock clock, String botLogin, URI api) {
        this.tokens = tokens;
        this.clock = clock;
        this.botLogin = botLogin;
        this.api = api;
    }

    @Override
    public OptionalLong publish(Job job, LongFunction<Optional<Evidence>> reserve) {
        try {
            var token = tokens.issueForAuthorNotification(job.pr().scope());
            String repository = "repos/" + token.repository();
            int number = job.pr().number();
            var pr = exchange(token, repository + "/pulls/" + number, null);
            var head = pr.path("head").path("sha");
            if (id(pr.path("base").path("repo").path("id")) != job.pr().scope().repositoryId()
                    || id(pr.path("number")) != number || !pr.path("draft").isBoolean()
                    || !pr.path("state").isString() || !head.isString()) {
                throw unavailable();
            }
            if (!"open".equals(pr.path("state").asString()) || pr.path("draft").asBoolean()
                    || !job.headSha().equals(head.asString())) {
                return OptionalLong.empty();
            }
            JsonNode author = pr.path("user");
            String login = author.path("login").asString();
            long recipient = id(author.path("id"));
            if (!"User".equals(author.path("type").asString()) || !author.path("login").isString()
                    || !USER_LOGIN.matcher(login).matches()
                    || "ghost".equalsIgnoreCase(login) || recipient <= 0) {
                return OptionalLong.empty();
            }
            if (!token.expiresAt().isAfter(clock.instant().plusSeconds(10))) throw unavailable();
            var evidence = reserve.apply(recipient);
            if (evidence.isEmpty()) return OptionalLong.empty();

            String link = "https://github.com/" + token.repository() + "/pull/" + number;
            String body = message(job, evidence.get(), login, link);
            // GitHub has no conditional comment creation or idempotency key. The committed permit
            // prevents any later retry of this POST, including when we cannot prove the outcome.
            var comment = exchange(token, repository + "/issues/" + number + "/comments", body);
            long commentId = id(comment.path("id"));
            if (commentId <= 0 || !body.equals(comment.path("body").asString())
                    || !botLogin.equalsIgnoreCase(comment.path("user").path("login").asString())
                    || !"Bot".equals(comment.path("user").path("type").asString())) {
                throw unavailable();
            }
            return OptionalLong.of(commentId);
        } catch (AuthorizationDenied denied) {
            throw new AuthorizationDenied();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ignored) {
            throw unavailable();
        }
    }

    private String message(Job job, Evidence evidence, String login, String link) {
        boolean ready = job.state() == State.READY_FOR_HUMAN;
        String status = ready ? "Ready for human review" : "Action required";
        String provider = ready
                ? "Baseline " + describe(evidence.baseline()) + ". Deep review " + describe(evidence.deep()) + "."
                : "The review workflow recorded feedback requiring action. See the PR conversation and review threads.";
        String next = ready
                ? "Please request a human review. Approval and any merge decision require human action."
                : "Please address or explicitly disposition the feedback, then request review of any fix.";
        return MARKER + "@" + login + " **" + status + "** for this [pull request](" + link + ").\n\n"
                + "- Head SHA: `" + job.headSha() + "`\n"
                + "- CI at dispatch: " + evidence.ci().name() + "\n"
                + "- Review evidence: " + provider + "\n\n" + next;
    }

    private String describe(ProviderEvidence evidence) {
        return switch (evidence) {
            case CODEX -> "has a recorded clean Codex review";
            case RECORDED -> "has a recorded clean provider review";
            case UNAVAILABLE -> "has no recorded provider provenance";
        };
    }

    private long id(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : -1;
    }

    private JsonNode exchange(InstallationToken token, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(api.resolve(path)).timeout(DEADLINE)
                .header("Authorization", token.authorizationHeader())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10");
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("body", body))));
        }
        int expected = body == null ? 200 : 201;
        var future = http.sendAsync(request.build(), info -> info.statusCode() == expected
                ? HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 262144).apply(info)
                : HttpResponse.BodySubscribers.replacing(""));
        try {
            var response = future.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != expected) throw unavailable();
            return json.readTree(response.body());
        } finally {
            future.cancel(true);
        }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("GitHub author notification unavailable");
    }
}
