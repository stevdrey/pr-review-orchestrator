package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.VerifiedDelivery;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit metadata routes, without interpretation of comment text, conclusions or review verdicts. */
final class GitHubWebhookPayload {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Map<String, Set<String>> ACTIONS = Map.of(
            "pull_request", Set.of("opened", "ready_for_review", "synchronize", "reopened", "closed", "edited", "converted_to_draft"),
            "pull_request_review", Set.of("submitted", "edited", "dismissed"),
            "pull_request_review_comment", Set.of("created", "edited", "deleted"),
            "pull_request_review_thread", Set.of("resolved", "unresolved"),
            "issue_comment", Set.of("created", "edited", "deleted"),
            "check_run", Set.of("created", "completed", "rerequested", "requested_action"),
            "check_suite", Set.of("completed", "requested", "rerequested"),
            "workflow_run", Set.of("requested", "in_progress", "completed"));

    static JsonNode parse(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            require(root != null && root.isObject());
            return root;
        } catch (tools.jackson.core.JacksonException ignored) {
            throw new IllegalArgumentException("Malformed webhook JSON");
        }
    }

    static VerifiedDelivery route(String type, String deliveryId, JsonNode root, byte[] body) {
        if (!ACTIONS.containsKey(type)) {
            throw new UnsupportedEvent();
        }
        String action = root.path("action").asText("");
        if (!ACTIONS.get(type).contains(action)) {
            throw new UnsupportedEvent();
        }
        var scope = new RepositoryScope(positiveId(root.path("installation").path("id")),
                positiveId(root.path("repository").path("id")));
        Integer pr = null;
        String sha = null;
        if (type.startsWith("pull_request")) {
            JsonNode pull = root.path("pull_request");
            pr = number(pull.path("number"));
            sha = sha(pull.path("head").path("sha"));
            switch (type) {
                case "pull_request_review" -> positiveId(root.path("review").path("id"));
                case "pull_request_review_comment" -> positiveId(root.path("comment").path("id"));
                case "pull_request_review_thread" -> positiveId(root.path("thread").path("id"));
                default -> { }
            }
        } else if (type.equals("issue_comment")) {
            if (!root.path("issue").path("pull_request").isObject()) {
                throw new UnsupportedEvent();
            }
            pr = number(root.path("issue").path("number"));
            positiveId(root.path("comment").path("id"));
            // General PR comments have no authoritative head SHA. A future worker must fetch it.
        } else {
            JsonNode check = root.path(type);
            positiveId(check.path("id"));
            sha = sha(check.path("head_sha"));
        }
        return new VerifiedDelivery(UUID.randomUUID(), deliveryId, type, action, scope, pr, sha,
                hash(body), UUID.randomUUID(), Instant.now());
    }

    private static long positiveId(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0);
        return node.longValue();
    }

    private static int number(JsonNode node) {
        long value = positiveId(node);
        require(value <= Integer.MAX_VALUE);
        return (int) value;
    }

    private static String sha(JsonNode node) {
        require(node.isString() && node.asText().matches("([0-9a-f]{40}|[0-9a-f]{64})"));
        return node.asText();
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Payload digest unavailable");
        }
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Malformed webhook metadata");
        }
    }

    static final class UnsupportedEvent extends RuntimeException {}
}
