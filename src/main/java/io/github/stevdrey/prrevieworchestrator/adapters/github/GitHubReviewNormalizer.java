package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Normalizes a complete, authorized GraphQL read. Webhook arrival order is not source freshness. */
public final class GitHubReviewNormalizer {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String notificationBotLogin;

    public GitHubReviewNormalizer() {
        this("");
    }

    public GitHubReviewNormalizer(String notificationBotLogin) {
        this.notificationBotLogin = Objects.requireNonNull(notificationBotLogin);
    }

    public Snapshot normalize(UUID observationId, UUID cycleId, PullRequestId pr, String expectedHead,
            Instant capturedAt, byte[] response) {
        return normalize(observationId, cycleId, pr, expectedHead, capturedAt, response, Map.of());
    }

    /** Severity classifications are explicit caller input; review prose cannot change dispositions. */
    public Snapshot normalize(UUID observationId, UUID cycleId, PullRequestId pr, String expectedHead,
            Instant capturedAt, byte[] response, Map<String, Severity> severities) {
        return normalize(observationId, cycleId, pr, expectedHead, capturedAt, response, severities, Set.of());
    }

    /** Explicit actionable IDs are advisory; every other nonblank general body requires human disposition. */
    public Snapshot normalize(UUID observationId, UUID cycleId, PullRequestId pr, String expectedHead,
            Instant capturedAt, byte[] response, Map<String, Severity> severities, Set<String> actionableIds) {
        return normalizeForRound(observationId, cycleId, pr, expectedHead, expectedHead, capturedAt,
                response, severities, actionableIds);
    }

    /** Reconcile a historical round against the PR head verified by the current authorized read. */
    public Snapshot normalizeForRound(UUID observationId, UUID cycleId, PullRequestId pr, String roundHeadSha,
            String expectedObservedHeadSha, Instant capturedAt, byte[] response, Map<String, Severity> severities,
            Set<String> actionableIds) {
        try {
            require(response.length <= 2 * 1024 * 1024);
            JsonNode root = JSON.readTree(response);
            require(root != null && root.isObject() && !root.has("errors"));
            JsonNode repository = root.path("data").path("repository");
            require(repository.path("databaseId").isIntegralNumber()
                    && repository.path("databaseId").canConvertToLong()
                    && repository.path("databaseId").longValue() == pr.scope().repositoryId());
            JsonNode pull = repository.path("pullRequest");
            require(pull.path("number").isIntegralNumber() && pull.path("number").canConvertToInt()
                    && pull.path("number").intValue() == pr.number());
            require(expectedObservedHeadSha.equals(string(pull, "headRefOid")));
            var sources = new ArrayList<Source>();
            for (JsonNode review : nodes(pull.path("reviews"))) {
                String id = string(review, "id");
                sources.add(new Source(id, Kind.REVIEW, id, null, null, author(review),
                        optionalString(review.path("commit"), "oid"), null, hash(body(review)),
                        Instant.parse(string(review, "updatedAt")), string(review, "state"),
                        null, null, null, null, null, Severity.UNKNOWN, feedback(review, actionableIds)));
            }
            for (JsonNode comment : nodes(pull.path("comments"))) {
                String id = string(comment, "id");
                sources.add(new Source(id, Kind.ISSUE_COMMENT, null, null, null, author(comment),
                        null, null, hash(body(comment)), Instant.parse(string(comment, "updatedAt")),
                        null, null, null, null, null, null, severities.getOrDefault(id, Severity.UNKNOWN),
                        ownNotification(comment) ? Feedback.NONE : feedback(comment, actionableIds)));
            }
            for (JsonNode thread : nodes(pull.path("reviewThreads"))) {
                String threadId = string(thread, "id");
                boolean resolved = bool(thread, "isResolved");
                boolean outdated = bool(thread, "isOutdated");
                sources.add(new Source(threadId, Kind.REVIEW_THREAD, null, threadId, null, null,
                        null, null, null, null, null, resolved, outdated, string(thread, "path"),
                        position(thread, "line"), position(thread, "originalLine"), Severity.UNKNOWN));
                for (JsonNode comment : nodes(thread.path("comments"))) {
                    String id = string(comment, "id");
                    sources.add(new Source(id, Kind.REVIEW_COMMENT,
                            optionalString(comment.path("pullRequestReview"), "id"), threadId,
                            optionalString(comment.path("replyTo"), "id"), author(comment),
                            optionalString(comment.path("commit"), "oid"),
                            optionalString(comment.path("originalCommit"), "oid"), hash(body(comment)),
                            Instant.parse(string(comment, "updatedAt")), null, resolved, outdated,
                            string(comment, "path"), position(comment, "line"), position(comment, "originalLine"),
                            severities.getOrDefault(id, Severity.UNKNOWN)));
                }
            }
            return new Snapshot(observationId, cycleId, pr, "github", roundHeadSha, expectedObservedHeadSha, capturedAt, sources);
        } catch (RuntimeException ignored) {
            // No provider text, response bodies, URLs or parser diagnostics in failure paths.
            throw new IllegalArgumentException("Incomplete or invalid GitHub review snapshot");
        }
    }

    private Feedback feedback(JsonNode node, Set<String> actionableIds) {
        if (body(node).isBlank()) return Feedback.NONE;
        return actionableIds.contains(string(node, "id")) ? Feedback.ACTIONABLE : Feedback.NEEDS_HUMAN;
    }

    private boolean ownNotification(JsonNode comment) {
        JsonNode actor = comment.path("author");
        if (notificationBotLogin.isEmpty() || !"Bot".equals(actor.path("__typename").asString())) return false;
        String login = actor.path("login").asString();
        // GraphQL Bot logins can omit the REST [bot] suffix.
        boolean sameApp = notificationBotLogin.equalsIgnoreCase(login)
                || notificationBotLogin.equalsIgnoreCase(login + "[bot]");
        return sameApp && body(comment).startsWith(GitHubAuthorNotices.MARKER);
    }

    private Iterable<JsonNode> nodes(JsonNode connection) {
        require(connection.path("nodes").isArray() && connection.path("pageInfo").path("hasNextPage").isBoolean()
                && !connection.path("pageInfo").path("hasNextPage").booleanValue());
        return connection.path("nodes");
    }
    private String author(JsonNode node) { return optionalString(node.path("author"), "login"); }
    private String body(JsonNode node) {
        require(node.path("body").isString()); return node.path("body").asString();
    }
    private String optionalString(JsonNode node, String field) {
        require(!node.isMissingNode());
        if (node.isNull()) return null;
        return string(node, field);
    }
    private String string(JsonNode node, String field) {
        require(node.path(field).isString() && !node.path(field).asString().isBlank());
        return node.path(field).asString();
    }
    private boolean bool(JsonNode node, String field) {
        require(node.path(field).isBoolean()); return node.path(field).booleanValue();
    }
    private Integer position(JsonNode node, String field) {
        require(node.has(field));
        if (node.path(field).isNull()) return null;
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt());
        return node.path(field).intValue();
    }
    private void require(boolean value) {
        if (!value) throw new IllegalArgumentException("Invalid GitHub evidence");
    }
    private String hash(String value) { return Sha256.hex(value.getBytes(StandardCharsets.UTF_8)); }
}
