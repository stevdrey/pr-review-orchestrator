package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler;
import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler.Policy;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.ErrorCode;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Failure;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Scoped GitHub reads for the sandbox worker. Only fully observed policy dependencies can pass. */
public final class GitHubSandboxCiReader implements SandboxCiReconciler.Reader {
    private static final URI API = URI.create("https://api.github.com/");
    private static final Duration API_DEADLINE = Duration.ofSeconds(3);
    private static final Duration OBSERVATION_DEADLINE = Duration.ofSeconds(20);
    private static final int PAGE_LIMIT = 100;
    private final JsonMapper json = JsonMapper.builder().build();
    private final GitHubInstallationTokens tokens;
    private final URI api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(API_DEADLINE)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public GitHubSandboxCiReader(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock) {
        this(new GitHubInstallationTokens(authorization, jwt, clock, API, API_DEADLINE), API);
    }

    GitHubSandboxCiReader(GitHubInstallationTokens tokens, URI api) {
        this.tokens = tokens;
        this.api = api;
    }

    @Override
    public Optional<CiEvidence> read(Policy policy, UUID observationId) {
        var deadline = System.nanoTime() + OBSERVATION_DEADLINE.toNanos();
        var pulls = issue(policy, GitHubReadOperation.PULL_REQUESTS);
        InstallationToken actions = null;
        try {
            if (!currentPr(policy, pulls, deadline)) return Optional.empty();
            actions = issue(policy, GitHubReadOperation.WORKFLOWS);
            var first = readAttempts(policy, actions, deadline);
            var second = readAttempts(policy, actions, deadline);
            if (!currentPr(policy, pulls, deadline)) return Optional.empty();

            Set<CiEvidence.Dependency> required = policy.workflowIds().stream()
                    .map(CiEvidence.Workflow::new).collect(Collectors.toUnmodifiableSet());
            boolean complete = first.isPresent() && first.equals(second)
                    && second.orElseThrow().size() == required.size();
            var observations = complete ? second.orElseThrow().values().stream()
                    .map(run -> new CiEvidence.Run(new CiEvidence.Workflow(run.workflow()), policy.head(), run.status()))
                    .toList() : List.<CiEvidence.Run>of();
            return Optional.of(new CiEvidence(observationId, policy.pr(), policy.head(), required, observations, complete));
        } finally {
            if (actions != null) tokens.revoke(actions);
            tokens.revoke(pulls);
        }
    }

    private InstallationToken issue(Policy policy, GitHubReadOperation operation) {
        try {
            return tokens.issue(policy.readScope(), operation);
        } catch (IllegalStateException unavailable) {
            throw unavailable();
        }
    }

    private boolean currentPr(Policy policy, InstallationToken token, long deadline) {
        var pr = get("repos/" + policy.repository() + "/pulls/" + policy.pr().number(), token, deadline);
        var repositoryId = policy.pr().scope().repositoryId();
        return pr.path("number").asInt() == policy.pr().number()
                && pr.path("base").path("repo").path("id").asLong() == repositoryId
                && pr.path("head").path("repo").path("id").asLong() == repositoryId
                && policy.head().equals(pr.path("head").path("sha").asString())
                && "open".equals(pr.path("state").asString())
                && pr.path("draft").isBoolean() && !pr.path("draft").asBoolean();
    }

    private Optional<Map<Long, Attempt>> readAttempts(Policy policy, InstallationToken token, long deadline) {
        String path = "repos/" + policy.repository() + "/actions/runs?head_sha=" + policy.head()
                + "&event=pull_request&per_page=" + PAGE_LIMIT;
        var result = get(path, token, deadline);
        var runs = result.path("workflow_runs");
        if (!runs.isArray() || !result.path("total_count").isIntegralNumber()
                || result.path("total_count").asLong() != runs.size() || runs.size() >= PAGE_LIMIT) {
            return Optional.empty();
        }

        Map<Long, Attempt> current = new LinkedHashMap<>();
        Map<Long, Set<Long>> sequences = new HashMap<>();
        for (JsonNode run : runs) {
            long workflow = run.path("workflow_id").asLong();
            if (!policy.workflowIds().contains(workflow)) continue;
            if (!policy.head().equals(run.path("head_sha").asString())
                    || !"pull_request".equals(run.path("event").asString())
                    || run.path("repository").path("id").asLong() != policy.pr().scope().repositoryId()) {
                return Optional.empty();
            }
            var associations = run.path("pull_requests");
            if (!associations.isArray() || associations.isEmpty()) return Optional.empty();
            if (!associated(policy, associations)) continue;

            var attempt = new Attempt(workflow, run.path("id").asLong(), run.path("run_number").asLong(),
                    run.path("run_attempt").asLong(), status(run));
            if (attempt.id() <= 0 || attempt.number() <= 0 || attempt.attempt() <= 0
                    || !sequences.computeIfAbsent(workflow, ignored -> new HashSet<>()).add(attempt.number())) {
                return Optional.empty();
            }
            current.merge(workflow, attempt, (first, next) -> first.number() > next.number() ? first : next);
        }
        return Optional.of(Map.copyOf(current));
    }

    private boolean associated(Policy policy, JsonNode associations) {
        for (var pr : associations) {
            if (pr.path("number").asInt() == policy.pr().number()
                    && pr.path("base").path("repo").path("id").asLong() == policy.pr().scope().repositoryId()) {
                return true;
            }
        }
        return false;
    }

    private record Attempt(long workflow, long id, long number, long attempt, Ci status) {}

    private Ci status(JsonNode run) {
        return switch (run.path("status").asString()) {
            case "completed" -> switch (run.path("conclusion").asString()) {
                case "success" -> Ci.PASSED;
                case "failure", "timed_out", "action_required", "startup_failure" -> Ci.FAILED;
                default -> Ci.UNKNOWN;
            };
            case "queued", "in_progress", "requested", "pending", "waiting" -> Ci.PENDING;
            default -> Ci.UNKNOWN;
        };
    }

    private JsonNode get(String path, InstallationToken token, long deadline) {
        long remaining = Math.min(deadline - System.nanoTime(), API_DEADLINE.toNanos());
        if (remaining <= 0) throw unavailable();

        var request = HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofNanos(remaining))
                .header("Authorization", token.authorizationHeader())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .GET().build();
        var exchange = http.sendAsync(request, info -> info.statusCode() == 200
                ? new GitHubInstallationTokens.BoundedBody(2 * 1024 * 1024)
                : HttpResponse.BodySubscribers.replacing(""));
        try {
            var response = exchange.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || response.headers().firstValue("Link").isPresent()) throw unavailable();
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            exchange.cancel(true);
        }
    }

    private Failure unavailable() {
        return new Failure(ErrorCode.REMOTE_UNAVAILABLE, true);
    }
}
