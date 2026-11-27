package io.github.stevdrey.prrevieworchestrator.adapters.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loopback GitHub transport for the feedback-loop tests. Only allowlisted routes are accepted. */
final class SandboxGitHub implements AutoCloseable {
    static final String REPOSITORY = "sandbox/review-fixture";
    static final String BOT = "fixture-app[bot]";
    static final String USER_TOKEN = "fixture_user_token";
    static final String APP_TOKEN = "fixture-installation-token";
    static final String PRIVATE_TEXT = "fixture-private-text";
    private static final String PR_PATH = "/repos/" + REPOSITORY + "/pulls/7";
    private static final String COMMENTS = "/repos/" + REPOSITORY + "/issues/7/comments";
    private static final Map<String, String> NOTICE_PERMISSIONS = Map.of("metadata", "read", "pull_requests", "write");

    record Comment(long id, String head, String body) {}
    private record ReviewReads(JsonNode graph, JsonNode reviews, JsonNode inline) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    private final List<Comment> comments = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final Instant tokenExpiry;
    private volatile String head;
    private volatile boolean rejectCommentReceipt;
    private volatile Map<String, String> trees = Map.of();
    private volatile List<Map<String, Object>> workflowRuns = List.of();
    private final AtomicInteger actionReads = new AtomicInteger();
    private final AtomicInteger pullReadFailures = new AtomicInteger();
    private final AtomicInteger reviewReads = new AtomicInteger();
    private volatile ReviewReads evidence;

    SandboxGitHub(String head, Instant tokenExpiry) throws IOException {
        this.head = head;
        this.tokenExpiry = tokenExpiry;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    String head() {
        return head;
    }

    void push(String sha) {
        head = sha;
    }

    List<Comment> comments() {
        return List.copyOf(comments);
    }

    List<String> unexpectedRequests() {
        return List.copyOf(unexpected);
    }

    void rejectCommentReceipt() {
        rejectCommentReceipt = true;
    }

    void trees(Map<String, String> trees) {
        this.trees = Map.copyOf(trees);
    }

    void workflowRuns(List<Map<String, Object>> runs) {
        workflowRuns = List.copyOf(runs);
    }

    int actionReads() { return actionReads.get(); }

    void failNextPullReads(int count) {
        pullReadFailures.set(count);
    }

    void reviewEvidence(String graph, String reviews, String inline) {
        evidence = new ReviewReads(json.readTree(graph), json.readTree(reviews), json.readTree(inline));
    }

    int reviewReads() { return reviewReads.get(); }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            boolean user = ("Bearer " + USER_TOKEN).equals(token);
            boolean app = ("Bearer " + APP_TOKEN).equals(token);
            String route = exchange.getRequestMethod() + " " + path;
            var review = evidence;
            if (route.equals("GET /user") && user) {
                send(exchange, 200, Map.of("id", 42, "type", "User"));
            } else if (route.equals("GET " + PR_PATH) && (user || app)) {
                if (pullReadFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                    send(exchange, 503, Map.of());
                    return;
                }
                send(exchange, 200, Map.of("number", 7, "state", "open", "draft", false, "locked", false,
                        "head", Map.of("sha", head, "repo", Map.of("id", 1001)), "base", Map.of("repo", Map.of("id", 1001)),
                        "user", Map.of("id", 52, "type", "User", "login", "fixture-author"), "body", PRIVATE_TEXT));
            } else if (route.startsWith("GET /repos/" + REPOSITORY + "/git/commits/") && app
                    && trees.containsKey(path.substring(path.lastIndexOf('/') + 1))) {
                String sha = path.substring(path.lastIndexOf('/') + 1);
                send(exchange, 200, Map.of("sha", sha, "tree", Map.of("sha", trees.get(sha))));
            } else if (route.equals("GET /repos/" + REPOSITORY + "/actions/runs") && app) {
                if (!("head_sha=" + head + "&event=pull_request&per_page=100").equals(exchange.getRequestURI().getQuery())) {
                    reject(exchange, route);
                    return;
                }
                actionReads.incrementAndGet();
                var runs = workflowRuns;
                send(exchange, 200, Map.of("total_count", runs.size(), "workflow_runs", runs));
            } else if (route.equals("POST /graphql") && app && review != null) {
                var query = json.readTree(exchange.getRequestBody().readAllBytes());
                if (!query.path("variables").equals(json.valueToTree(Map.of(
                        "owner", "sandbox", "repo", "review-fixture", "number", 7)))
                        || query.path("query").asString().split("\\bdatabaseId\\b", -1).length != 2) {
                    reject(exchange, route);
                    return;
                }
                reviewReads.incrementAndGet();
                send(exchange, 200, review.graph());
            } else if (route.equals("GET " + PR_PATH + "/reviews") && app && review != null) {
                send(exchange, 200, review.reviews());
            } else if (route.equals("GET " + PR_PATH + "/comments") && app && review != null) {
                send(exchange, 200, review.inline());
            } else if (route.equals("POST " + COMMENTS) && (user || app)) {
                var payload = json.readTree(exchange.getRequestBody().readAllBytes());
                if (!payload.isObject() || payload.size() != 1 || !payload.path("body").isString()) {
                    reject(exchange, route);
                    return;
                }
                String body = payload.path("body").asString();
                long id = 9001L + comments.size();
                comments.add(new Comment(id, head, body));
                if (rejectCommentReceipt) {
                    send(exchange, 500, Map.of("message", PRIVATE_TEXT));
                } else {
                    send(exchange, 201, Map.of("id", id, "body", body, "user",
                            user ? Map.of("id", 42, "type", "User") : Map.of("login", BOT, "type", "Bot")));
                }
            } else if (route.equals("POST /app/installations/101/access_tokens") && token != null
                    && token.startsWith("Bearer eyJ")) {
                var scope = json.readTree(exchange.getRequestBody().readAllBytes());
                var permissions = scope.path("permissions");
                if (!scope.path("repository_ids").equals(json.valueToTree(List.of(1001)))
                        || !Set.of(NOTICE_PERMISSIONS, GitHubReadOperation.PULL_REQUESTS.permissions(),
                                GitHubReadOperation.WORKFLOWS.permissions(), GitHubReadOperation.CONTENTS.permissions()).stream()
                                .anyMatch(expected -> permissions.equals(json.valueToTree(expected)))) {
                    reject(exchange, route);
                    return;
                }
                send(exchange, 201, Map.of("token", APP_TOKEN, "expires_at", tokenExpiry.toString(),
                        "repositories", List.of(Map.of("id", 1001, "full_name", REPOSITORY)),
                        "permissions", permissions));
            } else if (route.equals("DELETE /installation/token") && app) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                reject(exchange, route);
            }
        }
    }

    private void reject(HttpExchange exchange, String route) throws IOException {
        unexpected.add(route);
        send(exchange, 404, Map.of());
    }

    private void send(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = json.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        server.stop(0);
    }
}
