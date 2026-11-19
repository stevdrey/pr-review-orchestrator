package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.Evidence;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.Job;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.ProviderEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitHubCredentialsTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RepositoryScope SCOPE = new RepositoryScope(101, 1001);
    private static final String TOKEN = "fixture-only-token-not-a-real-credential";
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger revocations = new AtomicInteger();
    private final RepositoryAuthorization authorization = new RepositoryAuthorization(Set.of(SCOPE), s -> enabled.get());
    @TempDir Path directory;
    private Path keyFile;
    private KeyPair key;
    private GitHubAppJwt jwt;
    private HttpServer server;
    private GitHubInstallationTokens tokens;
    private int status = 201;
    private String response;
    private String requestBody;
    private String requestPath;
    private String requestAuthorization;
    private String requestVersion;
    private boolean revokeDuringRequest;
    private boolean slowBody;
    private String revokedAuthorization;

    @BeforeEach
    void setUp() throws Exception {
        key = newKey();
        keyFile = directory.resolve("app.pem");
        writeKey(keyFile, key);
        jwt = new GitHubAppJwt("Iv1.fixture", keyFile, CLOCK);
        response = response("2026-11-14T01:00:00Z", 1001, "read");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestPath = exchange.getRequestURI().getPath();
            requestAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestVersion = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (revokeDuringRequest) enabled.set(false);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            if (slowBody) {
                exchange.sendResponseHeaders(status, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes, 0, 1);
                    out.flush();
                    Thread.sleep(2_000);
                } catch (InterruptedException | java.io.IOException ignored) {
                    // Client abandoned the exchange at its deadline.
                }
                return;
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/installation/token", exchange -> {
            revocations.incrementAndGet();
            revokedAuthorization = exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        tokens = new GitHubInstallationTokens(authorization, jwt, CLOCK,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void signsVerifiableShortLivedJwtAndIssuesNarrowRepositoryToken() throws Exception {
        var token = tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS);
        assertEquals("Bearer " + TOKEN, token.authorizationHeader());
        assertEquals(NOW.plusSeconds(3600), token.expiresAt());
        assertEquals("InstallationToken[REDACTED]", token.toString());
        assertEquals("/app/installations/101/access_tokens", requestPath);
        assertEquals("2026-03-10", requestVersion);
        JsonNode body = json.readTree(requestBody);
        assertEquals(1, body.path("repository_ids").size());
        assertEquals(1001, body.path("repository_ids").get(0).asLong());
        assertEquals(json.valueToTree(GitHubReadOperation.PULL_REQUESTS.permissions()), body.path("permissions"));
        verifyJwt(requestAuthorization.substring("Bearer ".length()), key);
    }

    @Test
    void atomicKeyReplacementIsUsedOnNextIssuanceWithoutCaching() throws Exception {
        tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS);
        verifyJwt(requestAuthorization.substring(7), key);
        KeyPair replacement = newKey();
        Path next = directory.resolve("next.pem");
        writeKey(next, replacement);
        Files.move(next, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS);
        verifyJwt(requestAuthorization.substring(7), replacement);
        assertEquals(2, requests.get());
    }

    @Test
    void removedOrUnreadableSigningKeyFailsClosedAndRedactsDiagnostics() throws Exception {
        Files.writeString(keyFile, "fixture-private-key-material-do-not-log");
        var failure = assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS));
        assertSanitized(failure);
        assertEquals(0, requests.get());
        Files.delete(keyFile);
        assertSanitized(assertThrows(IllegalStateException.class, jwt::issue));
    }

    @Test
    void deniesForeignAndDisabledScopeBeforeAnyCredentialRequest() {
        assertThrows(SecurityException.class, () -> tokens.issue(new RepositoryScope(202, 1001), GitHubReadOperation.PULL_REQUESTS));
        enabled.set(false);
        assertThrows(SecurityException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS));
        assertEquals(0, requests.get());
    }

    @Test
    void rechecksRevocationBeforeReleasingIssuedToken() {
        revokeDuringRequest = true;
        // A denial stays a denial (not retryable unavailability), and the minted token is revoked remotely.
        assertSanitized(assertThrows(SecurityException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertEquals(1, requests.get());
        assertEquals(1, revocations.get());
        assertEquals("DELETE Bearer " + TOKEN, revokedAuthorization);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 401, 403, 404, 422, 429, 500})
    void remoteErrorsAndRedirectsAreNotRetriedOrLeaked(int remoteStatus) {
        status = remoteStatus;
        response = "fixture-private-key-material-do-not-log " + TOKEN;
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertEquals(1, requests.get());
        assertEquals(0, revocations.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13T23:00:00Z", "2026-11-14T00:00:30Z", "bad-expiry"})
    void expiredNearlyExpiredAndMalformedCredentialsAreRejected(String expiry) {
        response = response(expiry, 1001, "read");
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertEquals(1, revocations.get());
    }

    @Test
    void rejectsWrongRepositoryExcessPermissionsAndMalformedBody() {
        for (String bad : new String[] {response("2026-11-14T01:00:00Z", 2002, "read"),
                response("2026-11-14T01:00:00Z", 1001, "write"), "{}", "not-json " + TOKEN,
                response.replace(TOKEN, ""), response.replace(TOKEN, "bad\\ntoken"),
                response.replace("\"" + TOKEN + "\"", "1234"),
                response.replace("\"metadata\":\"read\"", "\"contents\":\"write\"")}) {
            response = bad;
            assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        }
        // Only the three responses carrying a well-formed live token (wrong repository, write, extra permission) are revoked.
        assertEquals(3, revocations.get());
    }

    @Test
    void oversizedResponseBodyIsRejected() {
        response = response("2026-11-14T01:00:00Z", 1001, "read")
                .replace("\"repositories\"", "\"padding\":\"" + "x".repeat(GitHubInstallationTokens.MAX_BODY_BYTES) + "\",\"repositories\"");
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
    }

    @Test
    void slowResponseBodyIsBoundedByTheExchangeDeadline() {
        slowBody = true;
        var bounded = new GitHubInstallationTokens(authorization, jwt, CLOCK,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofMillis(200));
        long started = System.nanoTime();
        assertSanitized(assertThrows(IllegalStateException.class, () -> bounded.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(1_500)) < 0);
    }

    @Test
    void eachOperationRequestsOnlyItsDocumentedReadPermission() throws Exception {
        for (var operation : GitHubReadOperation.values()) {
            response = json.writeValueAsString(java.util.Map.of("token", TOKEN, "expires_at", NOW.plusSeconds(3600).toString(),
                    "repositories", java.util.List.of(java.util.Map.of("id", 1001)), "permissions", operation.permissions()));
            tokens.issue(SCOPE, operation);
            assertEquals(json.valueToTree(operation.permissions()), json.readTree(requestBody).path("permissions"));
            assertTrue(operation.permissions().values().stream().allMatch("read"::equals));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixture-author", "mona-cat_octo"})
    void authorNoticeUsesScopedAppTokenAndPostsOnlySafeMetadata(String login) {
        var notice = notices();
        var posted = new AtomicReference<String>();
        server.createContext("/repos/owner/repository/pulls/7",
                exchange -> respond(exchange, 200, pull("a".repeat(40), login)));
        server.createContext("/repos/owner/repository/issues/7/comments", exchange -> {
            var payload = json.readTree(exchange.getRequestBody().readAllBytes());
            posted.set(payload.path("body").asString());
            if (!"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 403, "{}");
                return;
            }
            respond(exchange, 201, json.writeValueAsString(Map.of("id", 90, "body", posted.get(),
                    "user", Map.of("login", "fixture-app[bot]", "type", "Bot"))));
        });
        assertTrue(notice.publish(noticeJob(State.READY_FOR_HUMAN), (author, post) -> {
            assertEquals(123, author);
            assertNull(posted.get());
            assertEquals(90L, post.applyAsLong(new Evidence(Ci.PASSED, ProviderEvidence.CODEX, ProviderEvidence.RECORDED)));
            return true;
        }));
        var tokenRequest = json.readTree(requestBody);
        assertEquals(json.valueToTree(List.of(1001)), tokenRequest.path("repository_ids"));
        assertEquals(json.valueToTree(Map.of("metadata", "read", "pull_requests", "write")),
                tokenRequest.path("permissions"));
        assertTrue(posted.get().contains("@" + login + " **Ready for human review**"));
        assertTrue(posted.get().contains("github.com/owner/repository/pull/7"));
        assertTrue(posted.get().contains("a".repeat(40)));
        assertTrue(posted.get().contains("CI at dispatch: PASSED"));
        assertTrue(posted.get().contains("Codex"));
        assertTrue(posted.get().contains("Approval and any merge decision require human action"));
        assertFalse(posted.get().contains("PRIVATE_SOURCE"));
        assertFalse(posted.get().contains(TOKEN));
        assertEquals("DELETE Bearer " + TOKEN, revokedAuthorization);
        assertEquals(1, revocations.get());
    }

    @Test
    void normalizedEchoedBodyStillRecordsTheReceipt() {
        var notice = notices();
        server.createContext("/repos/owner/repository/pulls/7",
                exchange -> respond(exchange, 200, pull("a".repeat(40), "fixture-author")));
        server.createContext("/repos/owner/repository/issues/7/comments", exchange -> {
            String body = json.readTree(exchange.getRequestBody().readAllBytes()).path("body").asString();
            respond(exchange, 201, json.writeValueAsString(Map.of("id", 91, "body", body.replace("\n", "\r\n").strip(),
                    "user", Map.of("login", "fixture-app[bot]", "type", "Bot"))));
        });
        assertTrue(notice.publish(noticeJob(State.ACTION_REQUIRED), (recipient, post) -> post.applyAsLong(
                new Evidence(Ci.FAILED, ProviderEvidence.UNAVAILABLE, ProviderEvidence.UNAVAILABLE)) == 91));
    }

    @Test
    void tokenTooCloseToExpiryIsReportedAsNotPosted() {
        var reads = new AtomicInteger();
        var advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            // Preflight sees 11s of validity; the POST check sees 2s, below its 3s request deadline.
            @Override public Instant instant() { return NOW.plusSeconds(reads.getAndIncrement() == 0 ? 3589 : 3598); }
        };
        var notice = new GitHubAuthorNotices(tokens, advancing, "fixture-app[bot]",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        notices();
        server.createContext("/repos/owner/repository/pulls/7",
                exchange -> respond(exchange, 200, pull("a".repeat(40), "fixture-author")));
        var thrown = new AtomicReference<RuntimeException>();
        assertThrows(IllegalStateException.class, () -> notice.publish(noticeJob(State.ACTION_REQUIRED), (recipient, post) -> {
            try {
                return post.applyAsLong(new Evidence(Ci.FAILED, ProviderEvidence.UNAVAILABLE, ProviderEvidence.UNAVAILABLE)) > 0;
            } catch (RuntimeException failure) {
                thrown.set(failure);
                throw failure;
            }
        }));
        assertInstanceOf(io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticePublisher.NotPosted.class, thrown.get());
        assertEquals(1, revocations.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "draft", "closed", "locked", "invalid-author", "denied-permit"})
    void preflightMustProveHeadEligibilityAndAuthorBeforePosting(String condition) {
        var notice = notices();
        String head = condition.equals("stale") ? "b".repeat(40) : "a".repeat(40);
        String author = condition.equals("invalid-author") ? "author @someone-else" : "fixture-author";
        String pr = pull(head, author);
        if (condition.equals("draft")) pr = pr.replace("\"draft\":false", "\"draft\":true");
        if (condition.equals("locked")) pr = pr.replace("\"locked\":false", "\"locked\":true");
        if (condition.equals("closed")) pr = pr.replace("open", "closed");
        String response = pr;
        server.createContext("/repos/owner/repository/pulls/7", exchange -> respond(exchange, 200, response));
        assertFalse(notice.publish(noticeJob(State.ACTION_REQUIRED), (recipient, post) -> {
            assertEquals("denied-permit", condition);
            return false;
        }));
        assertEquals(1, requests.get()); // an unexpected comment POST would hit the catch-all handler
        assertEquals(1, revocations.get());
    }

    @Test
    void actionNoticeExplainsNextStepAndDoesNotRetryAnUncertainPost() {
        var notice = notices();
        var posts = new AtomicInteger();
        var posted = new AtomicReference<String>();
        server.createContext("/repos/owner/repository/pulls/7",
                exchange -> respond(exchange, 200, pull("a".repeat(40), "fixture-author")));
        server.createContext("/repos/owner/repository/issues/7/comments", exchange -> {
            posts.incrementAndGet();
            posted.set(json.readTree(exchange.getRequestBody().readAllBytes()).path("body").asString());
            respond(exchange, 500, "{\"body\":\"PRIVATE_SOURCE\"}");
        });
        assertSanitized(assertThrows(IllegalStateException.class, () -> notice.publish(noticeJob(State.ACTION_REQUIRED),
                (recipient, post) -> post.applyAsLong(
                        new Evidence(Ci.FAILED, ProviderEvidence.UNAVAILABLE, ProviderEvidence.UNAVAILABLE)) > 0)));
        assertEquals(1, posts.get());
        assertEquals(1, revocations.get());
        assertTrue(posted.get().contains("@fixture-author **Action required**"));
        assertTrue(posted.get().contains("CI at dispatch: FAILED"));
        assertTrue(posted.get().contains("address or explicitly disposition the feedback"));
        assertFalse(posted.get().contains("PRIVATE_SOURCE"));
    }

    private GitHubAuthorNotices notices() {
        response = json.writeValueAsString(Map.of("token", TOKEN, "expires_at", NOW.plusSeconds(3600).toString(),
                "repositories", List.of(Map.of("id", 1001, "full_name", "owner/repository")),
                "permissions", Map.of("metadata", "read", "pull_requests", "write")));
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new GitHubAuthorNotices(tokens, CLOCK, "fixture-app[bot]", origin);
    }

    private Job noticeJob(State state) {
        return new Job(1, new PullRequestId(SCOPE, 7), "a".repeat(40), state, 12, 1);
    }

    private String pull(String head, String author) {
        return """
                {"number":7, "state":"open", "draft":false, "locked":false, "head":{"sha":"%s"}, "base":{"repo":{"id":1001}},
                "user":{"id":123,"login":"%s","type":"User"}, "body":"PRIVATE_SOURCE"}
                """.formatted(head, author);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String content) throws java.io.IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    @Test
    void networkFailureIsSanitized() {
        server.stop(0);
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
    }

    private void verifyJwt(String value, KeyPair signingKey) throws Exception {
        String[] parts = value.split("\\.");
        assertEquals(3, parts.length);
        var decoder = Base64.getUrlDecoder();
        JsonNode header = json.readTree(decoder.decode(parts[0]));
        assertEquals("RS256", header.path("alg").asString());
        JsonNode claims = json.readTree(decoder.decode(parts[1]));
        assertEquals("Iv1.fixture", claims.path("iss").asString());
        assertEquals(NOW.minusSeconds(60).getEpochSecond(), claims.path("iat").asLong());
        assertEquals(NOW.plusSeconds(540).getEpochSecond(), claims.path("exp").asLong());
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(signingKey.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(decoder.decode(parts[2])));
    }

    private String response(String expiry, long repository, String permission) {
        return "{\"token\":\"" + TOKEN + "\",\"expires_at\":\"" + expiry + "\",\"repositories\":[{\"id\":" + repository
                + "}],\"permissions\":{\"metadata\":\"read\",\"pull_requests\":\"" + permission + "\"}}";
    }

    private static KeyPair newKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void writeKey(Path path, KeyPair pair) throws Exception {
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
    }

    private void assertSanitized(Throwable failure) {
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(TOKEN));
        assertFalse(output.toString().contains("fixture-private-key-material"));
        assertFalse(output.toString().contains(keyFile.toString()));
        assertNull(failure.getCause());
        assertFalse(jwt.toString().contains("Iv1.fixture"));
    }
}
