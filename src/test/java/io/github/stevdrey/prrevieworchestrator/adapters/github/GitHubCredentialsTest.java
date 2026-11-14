package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
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
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 401, 403, 404, 422, 429, 500})
    void remoteErrorsAndRedirectsAreNotRetriedOrLeaked(int remoteStatus) {
        status = remoteStatus;
        response = "fixture-private-key-material-do-not-log " + TOKEN;
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13T23:00:00Z", "2026-11-14T00:00:30Z", "bad-expiry"})
    void expiredNearlyExpiredAndMalformedCredentialsAreRejected(String expiry) {
        response = response(expiry, 1001, "read");
        assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
    }

    @Test
    void rejectsWrongRepositoryExcessPermissionsAndMalformedBody() {
        for (String bad : new String[] {response("2026-11-14T01:00:00Z", 2002, "read"),
                response("2026-11-14T01:00:00Z", 1001, "write"), "{}", "not-json " + TOKEN,
                response.replace(TOKEN, ""), response.replace(TOKEN, "bad\\ntoken"),
                response.replace("\"metadata\":\"read\"", "\"contents\":\"write\"")}) {
            response = bad;
            assertSanitized(assertThrows(IllegalStateException.class, () -> tokens.issue(SCOPE, GitHubReadOperation.PULL_REQUESTS)));
        }
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
