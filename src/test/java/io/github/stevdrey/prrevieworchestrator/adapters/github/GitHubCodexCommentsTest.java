package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.*;
import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials.Credential;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class GitHubCodexCommentsTest {
    static final RepositoryScope SCOPE = new RepositoryScope(101, 1001);
    static final String HEAD = "a".repeat(40);
    static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    static final String TOKEN = "fixture_secret_not_real";
    final ReviewRequest request = new ReviewRequest(new PullRequestId(SCOPE, 7), HEAD, "BASELINE", "codex",
            ReviewRequest.Trigger.PR_READY, 123, false);
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    URI api;
    final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger writes = new AtomicInteger();
    Credential credential;
    String user, pr, receipt;
    int postStatus;
    int readStatus;
    boolean slowPost, oversized, changeHead;
    String sentBody;
    String sentToken;

    @BeforeEach void setup() throws Exception {
        credential = new Credential(SCOPE, 42, "owner/repo", TOKEN, NOW.plusSeconds(300));
        user = "{\"id\":42,\"type\":\"User\"}";
        pr = pr(HEAD, false, "open", 1001);
        receipt = "{\"id\":9001,\"user\":{\"id\":42,\"type\":\"User\"},\"body\":\"@codex review\"}";
        postStatus = 201;
        readStatus = 200;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            boolean post = exchange.getRequestMethod().equals("POST");
            String body = path.equals("/user") ? user : pr;
            int status = readStatus;
            if (post) {
                writes.incrementAndGet();
                sentBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sentToken = exchange.getRequestHeaders().getFirst("Authorization");
                body = receipt;
                status = postStatus;
            } else if (changeHead && writes.get() > 0) body = pr("b".repeat(40), false, "open", 1001);
            if (oversized) body = " ".repeat(262145);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/leaked-token-target");
            exchange.sendResponseHeaders(status, slowPost && post ? 0 : bytes.length);
            try (var output = exchange.getResponseBody()) {
                if (slowPost && post) {
                    output.write('{'); output.flush(); Thread.sleep(2000);
                }
                output.write(bytes);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* bounded client abandoned response */ }
        });
        server.start();
        api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
    @AfterEach void cleanup() { server.stop(0); executor.shutdownNow(); }
    String pr(String head, boolean draft, String state, long repo) {
        return "{\"number\":7,\"state\":\"" + state + "\",\"draft\":" + draft
                + ",\"head\":{\"sha\":\"" + head + "\"},\"base\":{\"repo\":{\"id\":" + repo + "}}}";
    }
    GitHubCodexComments client() { return new GitHubCodexComments(scope -> credential, Clock.fixed(NOW, ZoneOffset.UTC), api); }
    void code(ReviewRequestFailure.Code code, Runnable action) {
        var failure = assertThrows(ReviewRequestFailure.class, action::run);
        assertEquals(code, failure.code());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(TOKEN));
    }

    @Test void fixedCommandVerifiedIdentityAndFreshHeadWithNoCustomText() {
        var guards = new AtomicInteger();
        assertEquals(9001, client().create(request, 42, guards::incrementAndGet));
        assertEquals(1, guards.get());
        assertEquals(1, writes.get());
        assertEquals("{\"body\":\"@codex review\"}", sentBody);
        assertEquals("Bearer " + TOKEN, sentToken);
        assertEquals(List.of("/user", "/repos/owner/repo/pulls/7", "/repos/owner/repo/issues/7/comments", "/repos/owner/repo/pulls/7"), paths);
        assertFalse(credential.toString().contains(TOKEN));
    }

    @Test void wrongActorAndBotTokenDoNotWrite() {
        user = "{\"id\":43,\"type\":\"User\"}";
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        user = "{\"id\":42,\"type\":\"Bot\"}";
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        assertEquals(0, writes.get());
    }

    @Test void expiredWrongScopeAndWrongConfiguredActorAreRejectedWithoutNetwork() {
        credential = new Credential(SCOPE, 42, "owner/repo", TOKEN, NOW);
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        credential = new Credential(new RepositoryScope(202, 2002), 42, "owner/repo", TOKEN, NOW.plusSeconds(300));
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        credential = new Credential(SCOPE, 43, "owner/repo", TOKEN, NOW.plusSeconds(300));
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        assertTrue(paths.isEmpty());
    }

    @Test void foreignRepositoryStaleHeadDraftClosedAndMalformedReadinessNeverWrite() {
        pr = pr(HEAD, false, "open", 2002);
        code(ACTOR_DENIED, () -> client().create(request, 42, () -> {}));
        pr = pr("b".repeat(40), false, "open", 1001);
        code(STALE_HEAD, () -> client().create(request, 42, () -> {}));
        pr = pr(HEAD, true, "open", 1001);
        code(PR_NOT_READY, () -> client().create(request, 42, () -> {}));
        pr = pr(HEAD, false, "closed", 1001);
        code(PR_NOT_READY, () -> client().create(request, 42, () -> {}));
        pr = pr(HEAD, false, "open", 1001).replace("\"draft\":false,", "");
        code(PR_NOT_READY, () -> client().create(request, 42, () -> {}));
        assertEquals(0, writes.get());
    }

    @Test void finalAuthorizationAndBudgetRevocationPreventsPost() {
        code(POLICY_DENIED, () -> client().create(request, 42, () -> { throw new ReviewRequestFailure(POLICY_DENIED); }));
        assertThrows(AuthorizationDenied.class, () -> client().create(request, 42, () -> { throw new AuthorizationDenied(); }));
        assertEquals(0, writes.get());
    }

    @Test void redirectsAreNotFollowedAndRemoteErrorsNeverLeak() {
        readStatus = 302;
        code(REMOTE_UNAVAILABLE, () -> client().create(request, 42, () -> {}));
        assertEquals(List.of("/user"), paths);
        readStatus = 200;
        postStatus = 403;
        receipt = TOKEN;
        code(OUTCOME_UNKNOWN, () -> client().create(request, 42, () -> {}));
        assertEquals(1, writes.get());
        assertFalse(paths.contains("/leaked-token-target"));
    }

    @Test void slowResponseBodyIsBoundedAndNeverRetried() {
        slowPost = true;
        assertTimeout(Duration.ofSeconds(3), () -> code(OUTCOME_UNKNOWN, () -> client().create(request, 42, () -> {})));
        assertEquals(1, writes.get());
    }

    @Test void oversizedReadCannotTriggerWriteAndHeadChangeAfterPostIsUncertain() {
        oversized = true;
        code(REMOTE_UNAVAILABLE, () -> client().create(request, 42, () -> {}));
        assertEquals(0, writes.get());
        oversized = false;
        changeHead = true;
        code(OUTCOME_UNKNOWN, () -> client().create(request, 42, () -> {}));
        assertEquals(1, writes.get());
    }

    @Test void invalidReceiptCannotBeReportedAsCreated() {
        receipt = "{\"id\":9001,\"user\":{\"id\":99,\"type\":\"Bot\"},\"body\":\"" + TOKEN + "\"}";
        code(OUTCOME_UNKNOWN, () -> client().create(request, 42, () -> {}));
        assertEquals(1, writes.get());
    }
}
