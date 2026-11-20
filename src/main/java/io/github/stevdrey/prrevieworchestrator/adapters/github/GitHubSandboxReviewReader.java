package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubSandboxReadClient.JSON;
import static io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubSandboxReadClient.unavailable;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler.Policy;
import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewReconciler;
import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads complete review evidence using the sandbox installation's Pull Requests read permission. */
public final class GitHubSandboxReviewReader implements SandboxReviewReconciler.Reader {
    private static final int REST_PAGE_LIMIT = 100;
    private static final String QUERY = """
            query SandboxReviewEvidence($owner: String!, $repo: String!, $number: Int!) {
              repository(owner:$owner, name:$repo) {
                databaseId
                pullRequest(number:$number) {
                  number headRefOid state isDraft
                  comments(first:30) {
                    totalCount pageInfo { hasNextPage }
                    nodes { id body updatedAt author { __typename login } }
                  }
                  reviews(first:30) {
                    totalCount pageInfo { hasNextPage }
                    nodes { id databaseId state body updatedAt author { __typename login } }
                  }
                  reviewThreads(first:30) {
                    totalCount pageInfo { hasNextPage }
                    nodes {
                      id isResolved isOutdated path line originalLine
                      comments(first:30) {
                        totalCount pageInfo { hasNextPage }
                        nodes {
                          id databaseId body updatedAt author { __typename login } path line originalLine
                          pullRequestReview { id } replyTo { id }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;
    private final GitHubInstallationTokens tokens;
    private final GitHubSandboxReadClient github;
    private final GitHubReviewNormalizer normalizer;
    private final Clock clock;

    public GitHubSandboxReviewReader(RepositoryAuthorization authorization, GitHubAppJwt jwt, Clock clock,
            GitHubReviewNormalizer normalizer) {
        this(new GitHubInstallationTokens(authorization, jwt, clock, GitHubSandboxReadClient.API,
                GitHubSandboxReadClient.API_DEADLINE), GitHubSandboxReadClient.API, clock, normalizer);
    }

    GitHubSandboxReviewReader(GitHubInstallationTokens tokens, URI api, Clock clock, GitHubReviewNormalizer normalizer) {
        this.tokens = tokens;
        this.github = new GitHubSandboxReadClient(api, tokens);
        this.clock = clock;
        this.normalizer = normalizer;
    }

    @Override
    public Optional<Snapshot> read(Policy policy, UUID observationId, UUID cycleId) {
        long deadline = github.observationDeadline();
        InstallationToken token;
        try {
            token = tokens.issue(policy.readScope(), GitHubReadOperation.PULL_REQUESTS);
        } catch (IllegalStateException unavailable) {
            throw unavailable();
        }
        try {
            if (!github.currentPr(policy, token, deadline)) return Optional.empty();
            var started = clock.instant();
            var repository = policy.repository().split("/", 2);
            var query = JSON.writeValueAsString(Map.of("query", QUERY, "variables",
                    Map.of("owner", repository[0], "repo", repository[1], "number", policy.pr().number())));
            var response = github.graphQl(policy, query, token, deadline);
            require(!response.has("errors"));
            var pull = response.path("data").path("repository").path("pullRequest");
            if (!policy.head().equals(pull.path("headRefOid").asString())
                    || !"OPEN".equals(pull.path("state").asString())
                    || !pull.path("isDraft").isBoolean() || pull.path("isDraft").asBoolean()) throw unavailable();
            complete(pull.path("comments"));

            // GraphQL Commit fields require additional permissions. Use scoped REST reviews/comments
            // for commit IDs and join the same source revisions to GraphQL thread metadata.
            String path = github.pullPath(policy);
            var restReviews = indexedRestCollection(policy, path + "/reviews", token, deadline);
            var restComments = indexedRestCollection(policy, path + "/comments", token, deadline);
            if (!github.currentPr(policy, token, deadline)) return Optional.empty();
            joinCommitEvidence(pull, restReviews, restComments);
            return Optional.of(normalizer.normalize(observationId, cycleId, policy.pr(), policy.head(),
                    started, JSON.writeValueAsBytes(response)));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        } finally {
            tokens.revoke(token);
        }
    }

    private void joinCommitEvidence(JsonNode pull, Map<Long, JsonNode> restReviews, Map<Long, JsonNode> restComments) {
        var reviewIds = new HashMap<String, Long>();
        for (var review : complete(pull.path("reviews"))) {
            long id = positiveId(review.path("databaseId"));
            var match = restReviews.remove(id);
            require(match != null && review.path("state").equals(match.path("state"))
                    && review.path("body").equals(match.path("body")));
            setCommit(review, "commit", match, "commit_id");
            require(reviewIds.putIfAbsent(review.path("id").asString(), id) == null);
        }
        for (var thread : complete(pull.path("reviewThreads"))) {
            for (var comment : complete(thread.path("comments"))) {
                var match = restComments.remove(positiveId(comment.path("databaseId")));
                require(match != null && comment.path("body").equals(match.path("body"))
                        && comment.path("updatedAt").equals(match.path("updated_at"))
                        && comment.path("path").equals(match.path("path")));
                var graphReview = comment.path("pullRequestReview");
                var restReview = match.path("pull_request_review_id");
                require(graphReview.isNull() ? restReview.isNull()
                        : Objects.equals(reviewIds.get(graphReview.path("id").asString()), positiveId(restReview)));
                setCommit(comment, "commit", match, "commit_id");
                setCommit(comment, "originalCommit", match, "original_commit_id");
            }
        }
        require(restReviews.isEmpty() && restComments.isEmpty());
    }

    private Map<Long, JsonNode> indexedRestCollection(Policy policy, String path, InstallationToken token, long deadline) {
        var items = github.get(policy, path + "?per_page=" + REST_PAGE_LIMIT, token, deadline);
        require(items.isArray() && items.size() < REST_PAGE_LIMIT);
        var indexed = new HashMap<Long, JsonNode>();
        for (var item : items) require(indexed.putIfAbsent(positiveId(item.path("id")), item) == null);
        return indexed;
    }

    private JsonNode complete(JsonNode connection) {
        var nodes = connection.path("nodes");
        var next = connection.path("pageInfo").path("hasNextPage");
        var count = connection.path("totalCount");
        require(nodes.isArray() && next.isBoolean() && !next.asBoolean() && count.isIntegralNumber()
                && count.canConvertToInt() && count.asInt() == nodes.size());
        return nodes;
    }

    private long positiveId(JsonNode id) {
        require(id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0);
        return id.asLong();
    }

    private void setCommit(JsonNode target, String graphqlField, JsonNode source, String restField) {
        var sha = source.path(restField);
        if (sha.isNull()) {
            ((ObjectNode) target).putNull(graphqlField);
        } else {
            require(sha.isString() && CommitSha.isValid(sha.asString()));
            ((ObjectNode) target).putObject(graphqlField).put("oid", sha.asString());
        }
    }

    private void require(boolean valid) {
        if (!valid) throw unavailable();
    }
}
