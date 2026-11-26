package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubSandboxDeviceOAuthTest {
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    private static final String HEAD = "a".repeat(40);
    private static final String USER_TOKEN = "ghu_fixtureSandboxToken";
    private static final String DEVICE_CODE = "fixtureSandboxDeviceCode";
    private final ProbeClock clock = new ProbeClock();
    private final UUID operator = UUID.randomUUID();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicBoolean authorized = new AtomicBoolean(true);
    private HttpServer server;
    private URI origin;
    private GitHubSandboxDeviceOAuth oauth;
    private volatile String fault = "";
    private volatile boolean waiting;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        oauth = probe(GitHubSandboxDeviceOAuth.Access.READ_ONLY);
    }

    private GitHubSandboxDeviceOAuth probe(GitHubSandboxDeviceOAuth.Access access) {
        var auth = new RepositoryAuthorization(Set.of(PR.scope()), ignored -> authorized.get());
        return new GitHubSandboxDeviceOAuth(auth, clock, "Iv1.fixture", new GitHubSandboxDeviceOAuth.Scope(
                PR, "sandbox/review-fixture", HEAD, 42), access, origin, origin);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void nativeDeviceGrantBindsTheOperatorSessionActorRepositoryAndHead() {
        waiting = true;
        var challenge = oauth.start(operator);
        assertEquals("ABCD-1234", challenge.userCode());
        assertFalse(challenge.toString().contains(challenge.userCode()));
        assertThrows(IllegalStateException.class, () -> oauth.poll(UUID.randomUUID()));
        assertEquals(clock.instant().plusSeconds(5), oauth.poll(operator).nextPoll());
        assertEquals(0, exchanges.get());

        clock.advance(5);
        assertNull(oauth.poll(operator).verified());
        clock.advance(5);
        assertEquals(clock.instant().plusSeconds(10), oauth.poll(operator).nextPoll());
        clock.advance(5);
        assertNull(oauth.poll(operator).verified());
        assertEquals(2, exchanges.get());

        clock.advance(5);
        assertEquals(new GitHubSandboxDeviceOAuth.Result(42, PR, HEAD), oauth.poll(operator).verified());
        assertEquals(3, exchanges.get());
        assertThrows(IllegalStateException.class, () -> oauth.poll(operator));
        assertThrows(IllegalStateException.class, () -> oauth.start(operator));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "write-permission", "second-repository", "stale-head", "pat"})
    void rejectsAnUnverifiedGrantWithoutLeakingTokenOrDeviceCode(String mismatch) {
        fault = mismatch;
        oauth.start(operator);
        clock.advance(5);
        var error = assertThrows(IllegalStateException.class, () -> oauth.poll(operator));
        assertFalse(error.toString().contains(USER_TOKEN));
        assertFalse(error.toString().contains(DEVICE_CODE));
        assertThrows(IllegalStateException.class, () -> oauth.poll(operator));
    }

    @Test
    void expiresOrRejectsLocalRevocationBeforeExchanging() {
        oauth.start(operator);
        clock.advance(5);
        authorized.set(false);
        assertThrows(AuthorizationDenied.class, () -> oauth.poll(operator));
        authorized.set(true);
        clock.advance(900);
        assertThrows(IllegalStateException.class, () -> oauth.poll(operator));
        assertEquals(0, exchanges.get());
    }

    @Test
    void verifiesAnExplicitWriteGrantWithoutReturningTheCredential() {
        fault = "write-permission";
        var writeProbe = probe(GitHubSandboxDeviceOAuth.Access.PULL_REQUESTS_WRITE);
        writeProbe.start(operator);
        clock.advance(5);
        var result = writeProbe.poll(operator).verified();
        assertEquals(new GitHubSandboxDeviceOAuth.Result(42, PR, HEAD), result);
        assertFalse(result.toString().contains(USER_TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "write-with-extra-permission"})
    void rejectsReadOnlyOrBroaderGrantsInWriteMode(String permission) {
        fault = permission;
        var writeProbe = probe(GitHubSandboxDeviceOAuth.Access.PULL_REQUESTS_WRITE);
        writeProbe.start(operator);
        clock.advance(5);
        assertThrows(IllegalStateException.class, () -> writeProbe.poll(operator));
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote-revocation", "local-revocation", "expanded-scope", "close", "expiry"})
    void dispatchCredentialRequiresFreshConsentAndExactScope(String stop) {
        fault = "write-permission";
        var auth = new RepositoryAuthorization(Set.of(PR.scope()), ignored -> authorized.get());
        var scope = new GitHubSandboxDeviceOAuth.Scope(PR, "sandbox/review-fixture", HEAD, 42);
        try (var credentials = new GitHubSandboxUserCredentials(auth, clock, "Iv1.fixture", scope, origin)) {
            assertThrows(ReviewRequestFailure.class, () -> credentials.current(PR.scope()));
            credentials.start(operator);
            clock.advance(5);
            assertThrows(IllegalStateException.class, () -> credentials.poll(UUID.randomUUID()));
            assertEquals(new GitHubSandboxDeviceOAuth.Result(42, PR, HEAD), credentials.poll(operator).verified());
            assertThrows(ReviewRequestFailure.class, () -> credentials.current(new RepositoryScope(101, 1002)));
            var grant = credentials.current(PR.scope());
            assertEquals(42, grant.actorId());
            assertEquals(PR.scope(), grant.scope());
            assertEquals("sandbox/review-fixture", grant.repository());
            assertEquals(clock.instant().plusSeconds(600), grant.expiresAt());
            assertEquals(USER_TOKEN, grant.token());
            assertFalse(grant.toString().contains(USER_TOKEN));
            switch (stop) {
                case "remote-revocation" -> fault = stop;
                case "expanded-scope" -> fault = "write-with-extra-permission";
                case "local-revocation" -> authorized.set(false);
                case "close" -> credentials.close();
                case "expiry" -> clock.advance(570);
                default -> fail("Unsupported fixture stop");
            }
            if (stop.equals("local-revocation")) {
                assertThrows(AuthorizationDenied.class, () -> credentials.current(PR.scope()));
            } else {
                var denied = assertThrows(ReviewRequestFailure.class, () -> credentials.current(PR.scope()));
                assertEquals(ReviewRequestFailure.Code.ACTOR_DENIED, denied.code());
                assertFalse(denied.toString().contains(USER_TOKEN));
            }
            authorized.set(true);
            fault = "write-permission";
            assertThrows(ReviewRequestFailure.class, () -> credentials.current(PR.scope()));
        }
    }

    private void handle(HttpExchange request) throws IOException {
        try (request) {
            String path = request.getRequestURI().getPath();
            var form = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/login/device/code") && request.getRequestMethod().equals("POST")
                    && form.equals("client_id=Iv1.fixture")) {
                reply(request, """
                        {"user_code":"ABCD-1234","device_code":"%s","expires_in":900,"interval":5,
                         "verification_uri":"https://github.com/login/device"}
                        """.formatted(DEVICE_CODE));
            } else if (path.equals("/login/oauth/access_token") && request.getRequestMethod().equals("POST")
                    && form.contains("repository_id=1001") && form.contains("device_code=" + DEVICE_CODE)
                    && form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")) {
                int count = exchanges.incrementAndGet();
                if (waiting && count <= 2) {
                    reply(request, count == 1 ? "{\"error\":\"authorization_pending\"}" : "{\"error\":\"slow_down\"}");
                } else {
                    reply(request, """
                            {"access_token":"%s","token_type":"bearer","scope":"","expires_in":28800}
                            """.formatted(fault.equals("pat") ? "ghp_fixturePATtoken" : USER_TOKEN));
                }
            } else if (request.getRequestMethod().equals("GET")
                    && ("Bearer " + USER_TOKEN).equals(request.getRequestHeaders().getFirst("Authorization"))) {
                switch (path) {
                    case "/user" -> reply(request, "{\"id\":" + (fault.equals("actor") ? 43 : 42) + ",\"type\":\"User\"}");
                    case "/user/installations" -> {
                        if (fault.equals("remote-revocation")) {
                            request.sendResponseHeaders(401, -1);
                            return;
                        }
                        String pullRequests = fault.startsWith("write-") ? "write" : "read";
                        String extra = fault.equals("write-with-extra-permission") ? ",\"actions\":\"read\"" : "";
                        reply(request, """
                                {"total_count":1,"installations":[{"id":101,"repository_selection":"selected","suspended_at":null,
                                "permissions":{"metadata":"read","pull_requests":"%s"%s}}]}
                                """.formatted(pullRequests, extra));
                    }
                    case "/user/installations/101/repositories" -> reply(request, """
                            {"total_count":%d,"repositories":[{"id":1001,"full_name":"sandbox/review-fixture"}]}
                            """.formatted(fault.equals("second-repository") ? 2 : 1));
                    case "/repos/sandbox/review-fixture/pulls/7" -> reply(request, """
                            {"number":7,"state":"open","draft":false,
                             "head":{"sha":"%s","repo":{"id":1001}},"base":{"repo":{"id":1001}}}
                            """.formatted(fault.equals("stale-head") ? "b".repeat(40) : HEAD));
                    default -> request.sendResponseHeaders(404, -1);
                }
            } else {
                request.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void reply(HttpExchange request, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        request.sendResponseHeaders(200, body.length);
        request.getResponseBody().write(body);
    }

    private static final class ProbeClock extends Clock {
        private Instant time = Instant.parse("2026-11-20T18:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
        void advance(long seconds) { time = time.plusSeconds(seconds); }
    }
}
