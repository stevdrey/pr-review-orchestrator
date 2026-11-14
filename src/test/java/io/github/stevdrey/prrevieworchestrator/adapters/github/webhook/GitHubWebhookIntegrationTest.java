package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.stevdrey.prrevieworchestrator.PrReviewOrchestratorApplication;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(classes = PrReviewOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubWebhookIntegrationTest {
    private static final String SECRET = "local-webhook-fixture-secret-0123456789";
    private static final String HEAD = "a".repeat(40);
    private static final Path SECRET_FILE = secretFile();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("orchestrator.github.webhook.enabled", () -> "true");
        registry.add("orchestrator.github.webhook.secret-file", SECRET_FILE::toString);
        registry.add("orchestrator.github.webhook.max-payload-bytes", () -> "4096");
        registry.add("orchestrator.github.repositories.101[0]", () -> "1001");
        registry.add("orchestrator.github.repositories.202[0]", () -> "2002");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedAuthorization() {
        jdbc.execute("TRUNCATE inbox_events, installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("""
                INSERT INTO installation_repositories (installation_id, repository_id, enabled)
                VALUES (101, 1001, true), (101, 2002, true), (202, 2002, true)
                """);
    }

    @AfterAll
    static void cleanup() throws Exception {
        CLIENT.close();
        Files.deleteIfExists(SECRET_FILE);
    }

    @Test
    void signedDeliveryCommitsOnlyMetadataBeforeAcknowledgement() throws Exception {
        byte[] body = pullRequest("opened", HEAD);
        String delivery = UUID.randomUUID().toString();
        var response = send("pull_request", delivery, body);
        assertEquals(202, response.statusCode(), response.body());
        var row = jdbc.queryForMap("SELECT * FROM inbox_events");
        assertThat(row).containsEntry("delivery_id", delivery).containsEntry("event_type", "pull_request")
                .containsEntry("event_action", "opened").containsEntry("installation_id", 101L)
                .containsEntry("repository_id", 1001L).containsEntry("pr_number", 7)
                .containsEntry("head_sha", HEAD).containsEntry("status", "PENDING")
                .containsEntry("attempts", 0).containsEntry("payload_ref", null)
                .containsEntry("payload_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(row.get("verified_at")).isNotNull();
        assertEquals(row.get("correlation_id").toString(), response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertEquals(0, count("outbox_commands"));
    }

    @Test
    void missingMalformedAndTamperedSignaturesNeverReachParsingOrInbox() throws Exception {
        byte[] invalidJson = "{ malformed private-fixture-data".getBytes(StandardCharsets.UTF_8);
        for (String signature : new String[] {"", "sha1=abc", "sha256=xyz", "sha256=" + "0".repeat(64)}) {
            var response = send(request(port, "pull_request", UUID.randomUUID().toString(), invalidJson,
                    signature.isEmpty() ? null : signature, "application/json", false));
            assertEquals(401, response.statusCode(), response.body());
            assertThat(response.body()).doesNotContain("private-fixture-data");
        }
        byte[] original = pullRequest("opened", HEAD);
        byte[] tampered = pullRequest("closed", HEAD);
        assertEquals(401, send(request(port, "pull_request", UUID.randomUUID().toString(), tampered,
                GitHubWebhookSecurityTest.sign(SECRET, original), "application/json", false)).statusCode());
        assertEquals(0, count("inbox_events"));
    }

    @Test
    void authenticatedMalformedMetadataAndAmbiguousJsonAreRejectedWithoutPersistence() throws Exception {
        for (String invalid : new String[] {"{", "[]", "null", "{} {}",
                "{\"installation\":{\"id\":101},\"installation\":{\"id\":202}}",
                new String(pullRequest("opened", HEAD), StandardCharsets.UTF_8).replace("101", "\"101\""),
                new String(pullRequest("opened", HEAD), StandardCharsets.UTF_8).replace(HEAD, "main")}) {
            assertEquals(400, send("pull_request", UUID.randomUUID().toString(), invalid.getBytes(StandardCharsets.UTF_8)).statusCode(), invalid);
        }
        byte[] body = pullRequest("opened", HEAD);
        for (String id : new String[] {"", "not-a-uuid", "a".repeat(300)}) {
            assertEquals(400, send("pull_request", id, body).statusCode());
        }
        var ambiguous = request(port, "pull_request", UUID.randomUUID().toString(), body,
                GitHubWebhookSecurityTest.sign(SECRET, body), "application/json", false)
                .header("X-GitHub-Event", "check_run");
        assertEquals(400, send(ambiguous).statusCode());
        var missing = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/github"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        assertEquals(400, send(missing).statusCode());
        assertEquals(0, count("inbox_events"));
    }

    @Test
    void sizeLimitAppliesToContentLengthAndChunkedBodiesAndMediaTypeIsStrict() throws Exception {
        byte[] oversized = (" ".repeat(4097)).getBytes(StandardCharsets.UTF_8);
        for (boolean chunked : new boolean[] {false, true}) {
            assertEquals(413, send(request(port, "pull_request", UUID.randomUUID().toString(), oversized,
                    GitHubWebhookSecurityTest.sign(SECRET, oversized), "application/json", chunked)).statusCode());
        }
        byte[] body = pullRequest("opened", HEAD);
        for (String media : new String[] {"text/plain", "application/*", "application/json; charset=UTF-16"}) {
            assertEquals(415, send(request(port, "pull_request", UUID.randomUUID().toString(), body,
                    GitHubWebhookSecurityTest.sign(SECRET, body), media, false)).statusCode());
        }
        assertEquals(415, send(request(port, "pull_request", UUID.randomUUID().toString(), body,
                GitHubWebhookSecurityTest.sign(SECRET, body), "application/json", false)
                .header("Content-Encoding", "gzip")).statusCode());
        assertEquals(0, count("inbox_events"));
    }

    @Test
    void authorizationChecksPairedAllowlistMembershipAndRevocationEvenForDuplicates() throws Exception {
        byte[] body = pullRequest("opened", HEAD);
        String id = UUID.randomUUID().toString();
        assertEquals(202, send("pull_request", id, body).statusCode());
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101 AND repository_id = 1001");
        assertEquals(403, send("pull_request", id, body).statusCode());
        jdbc.update("UPDATE installation_repositories SET enabled = true WHERE installation_id = 101 AND repository_id = 1001");
        for (String status : new String[] {"SUSPENDED", "REVOKED"}) {
            jdbc.update("UPDATE installations SET status = ? WHERE installation_id = 101", status);
            assertEquals(403, send("pull_request", id, body).statusCode());
        }
        jdbc.update("UPDATE installations SET status = 'ACTIVE' WHERE installation_id = 101");
        // The crossed pair is enabled in the DB but is not approved by the deployment allowlist.
        byte[] crossed = new String(body, StandardCharsets.UTF_8).replace("1001", "2002").getBytes(StandardCharsets.UTF_8);
        assertEquals(403, send("pull_request", UUID.randomUUID().toString(), crossed).statusCode());
        jdbc.update("DELETE FROM installation_repositories WHERE installation_id = 101 AND repository_id = 1001");
        assertEquals(403, send("pull_request", UUID.randomUUID().toString(), body).statusCode());
        assertEquals(1, count("inbox_events"));
    }

    @Test
    void unavailableAuthorizationAndMissingRotatedSecretFailClosed() throws Exception {
        byte[] body = pullRequest("opened", HEAD);
        jdbc.execute("ALTER TABLE installations RENAME TO unavailable_installations_fixture");
        try {
            assertEquals(403, send("pull_request", UUID.randomUUID().toString(), body).statusCode());
        } finally {
            jdbc.execute("ALTER TABLE unavailable_installations_fixture RENAME TO installations");
        }
        Files.delete(SECRET_FILE);
        try {
            var response = send("pull_request", UUID.randomUUID().toString(), body);
            assertEquals(503, response.statusCode());
            assertThat(response.body()).doesNotContain(SECRET, SECRET_FILE.toString());
        } finally {
            Files.writeString(SECRET_FILE, SECRET);
        }
        assertEquals(0, count("inbox_events"));
    }

    @Test
    void retriesAndRenamedDeliveryReplayHaveOneRowAndConflictingReuseFails() throws Exception {
        byte[] body = pullRequest("opened", HEAD);
        String id = UUID.randomUUID().toString();
        var original = send("pull_request", id, body);
        for (String retry : new String[] {id, UUID.randomUUID().toString()}) {
            var duplicate = send("pull_request", retry, body);
            assertEquals(200, duplicate.statusCode(), duplicate.body());
            assertEquals(original.headers().firstValue("X-Correlation-ID"), duplicate.headers().firstValue("X-Correlation-ID"));
        }
        assertEquals(409, send("pull_request", id, pullRequest("closed", HEAD)).statusCode());
        assertEquals(1, count("inbox_events"));
    }

    @Test
    void concurrentReplaysAreSerializedByPostgresWithoutDuplicateAcceptance() throws Exception {
        byte[] body = pullRequest("opened", HEAD);
        String signature = GitHubWebhookSecurityTest.sign(SECRET, body);
        String id = UUID.randomUUID().toString();
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 12; i++) {
            pending.add(CLIENT.sendAsync(request(port, "pull_request", i % 2 == 0 ? id : UUID.randomUUID().toString(),
                    body, signature, "application/json", false).build(), HttpResponse.BodyHandlers.ofString()));
        }
        var responses = pending.stream().map(CompletableFuture::join).toList();
        assertEquals(1, responses.stream().filter(response -> response.statusCode() == 202).count());
        assertEquals(11, responses.stream().filter(response -> response.statusCode() == 200).count());
        assertEquals(1, responses.stream().map(response -> response.headers().firstValue("X-Correlation-ID")).distinct().count());
        assertEquals(1, count("inbox_events"));
    }

    @Test
    void durableRetrySurvivesApplicationRestartAndOutOfOrderObservationsRetainTheirSha() throws Exception {
        byte[] old = pullRequest("opened", HEAD);
        String id = UUID.randomUUID().toString();
        var original = send("pull_request", id, old);
        assertEquals(202, original.statusCode());
        try (var restarted = new SpringApplicationBuilder(PrReviewOrchestratorApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--orchestrator.github.webhook.enabled=true", "--orchestrator.github.webhook.secret-file=" + SECRET_FILE,
                "--orchestrator.github.repositories.101[0]=1001")) {
            int restartedPort = ((org.springframework.boot.web.server.context.WebServerApplicationContext) restarted)
                    .getWebServer().getPort();
            var replay = send(request(restartedPort, "pull_request", id, old,
                    GitHubWebhookSecurityTest.sign(SECRET, old), "application/json", false));
            assertEquals(200, replay.statusCode());
            assertEquals(original.headers().firstValue("X-Correlation-ID"), replay.headers().firstValue("X-Correlation-ID"));
        }
        assertEquals(202, send("pull_request", UUID.randomUUID().toString(), pullRequest("synchronize", "b".repeat(40))).statusCode());
        assertEquals(202, send("pull_request", UUID.randomUUID().toString(), pullRequest("closed", HEAD)).statusCode());
        assertEquals(3, count("inbox_events"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE head_sha = ?", Integer.class, HEAD));
        assertEquals(0, count("review_cycles"));
    }

    @Test
    void deferredCommitFailureReturnsUnavailableAndLeavesNoAcceptedRow() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION reject_fixture_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private-fixture-diagnostic'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_fixture_commit AFTER INSERT ON inbox_events
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_fixture_commit()
                """);
        try {
            var response = send("pull_request", UUID.randomUUID().toString(), pullRequest("opened", HEAD));
            assertEquals(503, response.statusCode(), response.body());
            assertThat(response.body()).doesNotContain("private-fixture-diagnostic", SECRET);
            assertThat(response.headers().firstValue("X-Correlation-ID")).isEmpty();
            assertEquals(0, count("inbox_events"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_fixture_commit ON inbox_events");
            jdbc.execute("DROP FUNCTION reject_fixture_commit()");
        }
    }

    @Test
    void reviewThreadNodeIdsAreAcceptedForBothActionsWithoutNumericIds() throws Exception {
        // Routing fields from Octokit's resolved/unresolved schemas: node_id + comments, no thread.id.
        // https://github.com/octokit/webhooks/tree/main/payload-schemas/api.github.com/pull_request_review_thread
        for (String action : new String[] {"resolved", "unresolved"}) {
            byte[] body = relatedPullRequest(action, "\"thread\":{\"node_id\":\"PRRT_fixture\",\"comments\":[]}");
            String id = UUID.randomUUID().toString();
            var response = send("pull_request_review_thread", id, body);
            assertEquals(202, response.statusCode(), response.body());
            var row = jdbc.queryForMap("SELECT * FROM inbox_events WHERE delivery_id = ?", id);
            assertEquals("pull_request_review_thread", row.get("event_type"));
            assertEquals(action, row.get("event_action"));
            assertEquals(7, row.get("pr_number"));
            assertEquals(HEAD, row.get("head_sha"));
            assertEquals(200, send("pull_request_review_thread", id, body).statusCode());
        }
        assertEquals(2, count("inbox_events"));
        assertEquals(0, count("findings"));
    }

    @Test
    void missingNonStringOrBlankThreadNodeIdsNeverQueue() throws Exception {
        for (String metadata : new String[] {
                "\"thread\":{\"id\":3,\"comments\":[]}",
                "\"thread\":{\"node_id\":null,\"comments\":[]}",
                "\"thread\":{\"node_id\":3,\"comments\":[]}",
                "\"thread\":{\"node_id\":\"\",\"comments\":[]}",
                "\"thread\":{\"node_id\":\"   \",\"comments\":[]}"}) {
            assertEquals(400, send("pull_request_review_thread", UUID.randomUUID().toString(),
                    relatedPullRequest("resolved", metadata)).statusCode(), metadata);
        }
        assertEquals(0, count("inbox_events"));
    }

    @Test
    void editedReviewOrCommentCannotBeRelabeledAsPullRequestThroughUnsignedHeader() throws Exception {
        for (String type : new String[] {"pull_request_review", "pull_request_review_comment"}) {
            String metadata = type.equals("pull_request_review") ? "\"review\":{\"id\":1}" : "\"comment\":{\"id\":2}";
            byte[] body = relatedPullRequest("edited", metadata);
            String id = UUID.randomUUID().toString();
            // HMAC covers the shared action/PR fields, but not the event header. Reviews lack a root PR number.
            assertEquals(400, send("pull_request", id, body).statusCode());
            assertEquals(202, send(type, id, body).statusCode());
        }
        byte[] mismatched = new String(pullRequest("edited", HEAD), StandardCharsets.UTF_8)
                .replace("\"number\":7,\"installation\"", "\"number\":8,\"installation\"").getBytes(StandardCharsets.UTF_8);
        assertEquals(400, send("pull_request", UUID.randomUUID().toString(), mismatched).statusCode());
        assertEquals(2, count("inbox_events"));
    }

    @Test
    void deferredCommitWorkCannotOutliveTheConfiguredDatabaseTransactionBudget() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION delay_fixture_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(4); RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER delay_fixture_commit AFTER INSERT ON inbox_events
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION delay_fixture_commit()
                """);
        try {
            var response = send("pull_request", UUID.randomUUID().toString(), pullRequest("opened", HEAD));
            assertEquals(503, response.statusCode(), response.body());
            assertThat(response.headers().firstValue("X-Correlation-ID")).isEmpty();
            assertEquals(0, count("inbox_events"));
        } finally {
            jdbc.execute("DROP TRIGGER delay_fixture_commit ON inbox_events");
            jdbc.execute("DROP FUNCTION delay_fixture_commit()");
        }
    }

    @Test
    void eventRoutesPreserveDistinctReviewCommentThreadAndCiMetadata() throws Exception {
        String common = "\"installation\":{\"id\":101},\"repository\":{\"id\":1001}";
        String pull = "\"pull_request\":{\"number\":7,\"head\":{\"sha\":\"" + HEAD + "\"}}";
        for (String type : new String[] {"pull_request_review", "pull_request_review_comment", "pull_request_review_thread",
                "issue_comment", "check_run", "check_suite", "workflow_run"}) {
            String action;
            String metadata;
            switch (type) {
                case "pull_request_review" -> { action = "submitted"; metadata = pull + ",\"review\":{\"id\":1}"; }
                case "pull_request_review_comment" -> { action = "created"; metadata = pull + ",\"comment\":{\"id\":2}"; }
                case "pull_request_review_thread" -> { action = "resolved"; metadata = pull + ",\"thread\":{\"node_id\":\"PRRT_fixture\",\"comments\":[]}"; }
                case "issue_comment" -> { action = "created"; metadata = "\"issue\":{\"number\":7,\"pull_request\":{}},\"comment\":{\"id\":4}"; }
                default -> { action = "completed"; metadata = "\"" + type + "\":{\"id\":5,\"head_sha\":\"" + HEAD + "\"}"; }
            }
            byte[] body = ("{" + common + ",\"action\":\"" + action + "\"," + metadata + "}").getBytes(StandardCharsets.UTF_8);
            assertEquals(202, send(type, UUID.randomUUID().toString(), body).statusCode(), type);
            var row = jdbc.queryForMap("SELECT * FROM inbox_events WHERE event_type = ?", type);
            assertEquals(action, row.get("event_action"));
            assertEquals(type.equals("issue_comment") ? null : HEAD, row.get("head_sha"));
        }
        assertEquals(7, count("inbox_events"));
        assertEquals(0, count("findings"));
    }

    @Test
    void signedPingHasNoWorkAndUnsupportedTypesOrActionsCannotQueue() throws Exception {
        assertEquals(204, send("ping", UUID.randomUUID().toString(), "{\"hook_id\":123}".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(422, send("push", UUID.randomUUID().toString(), pullRequest("opened", HEAD)).statusCode());
        assertEquals(422, send("pull_request", UUID.randomUUID().toString(), pullRequest("unknown_action", HEAD)).statusCode());
        assertEquals(0, count("inbox_events"));
    }

    private HttpResponse<String> send(String type, String id, byte[] body) throws Exception {
        return send(request(port, type, id, body, GitHubWebhookSecurityTest.sign(SECRET, body), "application/json", false));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(int port, String type, String id, byte[] body, String signature,
            String media, boolean chunked) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/github"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", media)
                .header("X-GitHub-Event", type).header("X-GitHub-Delivery", id);
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return request.POST(chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private static byte[] relatedPullRequest(String action, String metadata) {
        return ("{\"action\":\"" + action + "\",\"installation\":{\"id\":101},\"repository\":{\"id\":1001},"
                + "\"pull_request\":{\"number\":7,\"head\":{\"sha\":\"" + HEAD + "\"}}," + metadata + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pullRequest(String action, String head) {
        return ("{\"action\":\"" + action + "\",\"number\":7,\"installation\":{\"id\":101},\"repository\":{\"id\":1001},"
                + "\"pull_request\":{\"number\":7,\"head\":{\"sha\":\"" + head + "\"},\"title\":\"café 🌎\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static Path secretFile() {
        try {
            return Files.writeString(Files.createTempFile("webhook-test-", ".secret"), SECRET);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot create test secret", failure);
        }
    }
}
