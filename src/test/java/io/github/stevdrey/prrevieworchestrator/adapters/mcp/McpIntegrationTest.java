package io.github.stevdrey.prrevieworchestrator.adapters.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.stevdrey.prrevieworchestrator.PrReviewOrchestratorApplication;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.PostgresLocks;
import io.github.stevdrey.prrevieworchestrator.application.DisposeGeneralFeedback;
import io.github.stevdrey.prrevieworchestrator.application.IngestReviewEvidence;
import io.github.stevdrey.prrevieworchestrator.application.ReviewLifecycle;
import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import io.github.stevdrey.prrevieworchestrator.domain.Sha256;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(classes = PrReviewOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String VERSION = "2025-11-25";
    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(64);
    private static final String READ = "1".repeat(64);
    private static final String PAUSE = "2".repeat(64);
    private static final String REVIEW = "3".repeat(64);
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    private static final Map<String, Object> TARGET =
            Map.of("installationId", 101, "repositoryId", 1001, "prNumber", 7, "headSha", HEAD);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("orchestrator.github.repositories.101[0]", () -> "1001");
        registry.add("orchestrator.github.repositories.202[0]", () -> "1001");
        registry.add("orchestrator.mcp.enabled", () -> "true");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReviewLifecycle lifecycle;
    @Autowired
    IngestReviewEvidence evidence;
    @Autowired
    DisposeGeneralFeedback dispositions;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("""
                INSERT INTO installation_repositories (installation_id, repository_id, enabled)
                VALUES (101, 1001, true), (101, 2002, true), (202, 1001, true)
                """);
        lifecycle.create(PR, HEAD);
        lifecycle.create(new PullRequestId(PR.scope(), 8), HEAD);
        lifecycle.create(new PullRequestId(new RepositoryScope(202, 1001), 7), HEAD);
        grant(READ, null, null, null);
    }

    @Test
    void initializesAndAdvertisesSchemasOverStreamableHttp() throws Exception {
        var init = initialize(VERSION);
        assertEquals(200, init.statusCode());
        assertEquals(VERSION, JSON.readTree(init.body()).at("/result/protocolVersion").asText());
        assertEquals(202, post(READ, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """).statusCode());
        var listing = post(READ, rpc("tools/list", Map.of()));
        assertEquals(200, listing.statusCode());
        assertEquals("no-store", listing.headers().firstValue("Cache-Control").orElseThrow());
        var tools = JSON.readTree(listing.body()).at("/result/tools");
        assertThat(tools.values()).extracting(tool -> tool.path("name").asText())
                .containsExactly("get_pr_status", "list_findings", "pause_cycle", "request_review");
        for (JsonNode tool : tools) {
            assertThat(tool.at("/inputSchema/required").values()).anyMatch(field -> field.asText().equals("headSha"));
            assertThat(tool.at("/inputSchema/additionalProperties").booleanValue()).isFalse();
        }

        var status = tool(READ, "get_pr_status", TARGET, "OK");
        assertEquals("NEW", status.path("state").asText());
        assertEquals("UNKNOWN", status.path("ci").asText());
        assertEquals(HEAD, status.path("headSha").asText());
        var get = HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + READ)
                .header("Accept", "text/event-stream").GET().build();
        assertEquals(405, CLIENT.send(get, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void negotiatesOnlyRevisionsWithSingleMessageHttp() throws Exception {
        String legacyVersion = "2025-03-26";
        String juneVersion = "2025-06-18";
        var fallback = initialize(legacyVersion);
        assertEquals(200, fallback.statusCode());
        assertEquals(VERSION, JSON.readTree(fallback.body()).at("/result/protocolVersion").asText());
        var june = initialize(juneVersion);
        assertEquals(200, june.statusCode());
        assertEquals(juneVersion, JSON.readTree(june.body()).at("/result/protocolVersion").asText());
        // Initialization negotiates from its body, even when the client sends an older header revision.
        var legacyHeader = post(READ, rpc("initialize", Map.of("protocolVersion", legacyVersion, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "fixture", "version", "1"))), legacyVersion);
        assertEquals(200, legacyHeader.statusCode());
        assertEquals(VERSION, JSON.readTree(legacyHeader.body()).at("/result/protocolVersion").asText());

        String ping = rpc("ping", Map.of());
        assertEquals(200, post(READ, ping, juneVersion).statusCode());
        // Each request validates header support independently of initialization.
        assertEquals(200, post(READ, ping, VERSION).statusCode());
        assertEquals(200, initialize(VERSION).statusCode());
        assertEquals(200, post(READ, ping, juneVersion).statusCode());
        assertEquals(400, post(READ, ping, legacyVersion).statusCode());
        assertEquals(400, post(READ, ping, null).statusCode());
        assertEquals(400, post(READ, "[" + ping + "]").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "ACKNOWLEDGED"})
    void listsOnePendingOccurrenceWhenAnOlderRoundsReadSeesAnEditedInlineFinding(String pendingStatus)
            throws Exception {
        UUID olderRound = cycle(101, 7, NEXT_HEAD);
        UUID currentRound = cycle(101, 7, HEAD);
        Instant observedAt = Instant.parse("2026-11-27T12:00:00Z");
        var closedRevision = inlineSource("a".repeat(64), observedAt, true);
        assertTrue(evidence.ingest(new ReviewEvidence.Snapshot(UUID.randomUUID(), currentRound, PR, "fixture", HEAD,
                observedAt, List.of(closedRevision))));
        jdbc.update("""
                UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer',
                    disposition_reason = 'Round decision' WHERE review_cycle_id = ?
                """, currentRound);
        var stableId = jdbc.queryForObject("SELECT stable_finding_id FROM findings", UUID.class);

        // A historical copy of the same revision remains superseded by its same-head occurrence.
        assertTrue(evidence.ingest(new ReviewEvidence.Snapshot(UUID.randomUUID(), olderRound, PR, "fixture",
                NEXT_HEAD, HEAD, observedAt.plusSeconds(1), List.of(closedRevision))));
        assertInlineFinding(stableId, "DISMISSED", true);
        var gates = new ReviewTransition.Gates(true, false, false, true, false, ReviewTransition.Ci.UNKNOWN);
        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), 0, HEAD, ReviewTransition.Action.MARK_READY,
                null, gates));
        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), 1, HEAD, ReviewTransition.Action.START_REVIEW,
                null, gates));
        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), 2, HEAD, ReviewTransition.Action.BASELINE_CLEAN,
                null, gates));

        var editedRevision = inlineSource("b".repeat(64), observedAt.plusSeconds(2), false);
        assertTrue(evidence.ingest(new ReviewEvidence.Snapshot(UUID.randomUUID(), olderRound, PR, "fixture",
                NEXT_HEAD, HEAD, observedAt.plusSeconds(2), List.of(editedRevision))));
        assertEquals(PullRequestReview.State.ACTION_REQUIRED, lifecycle.find(PR).orElseThrow().state());
        jdbc.update("UPDATE findings SET status = ? WHERE review_cycle_id = ?", pendingStatus, olderRound);
        assertInlineFinding(stableId, pendingStatus, false);
    }

    @Test
    void onlyReturnsRedactedFindingsForTheAuthorizedPrAcrossRoundsAndPages() throws Exception {
        UUID current = cycle(101, 7, HEAD);
        for (int i = 1; i <= 51; i++) finding(current, 101, HEAD, i);
        // A retained finding from an earlier head still feeds the clean gate, so it stays listed.
        finding(cycle(101, 7, NEXT_HEAD), 101, NEXT_HEAD, 100);
        finding(cycle(101, 8, HEAD), 101, HEAD, 101);
        finding(cycle(202, 7, HEAD), 202, HEAD, 102);

        var page = tool(READ, "list_findings", TARGET, "OK");
        assertEquals(50, page.path("findings").size());
        assertThat(page.toString()).doesNotContain("private-body-reference", "sourceId", "sourceProvider");
        assertEquals("OPEN", page.at("/findings/0/status").asText());
        assertEquals("ISSUE_COMMENT", page.at("/findings/0/sourceKind").asText());
        var next = tool(READ, "list_findings", with("after", page.path("nextCursor").asText()), "OK");
        assertThat(next.path("findings").values()).extracting(finding -> finding.path("id").asText())
                .containsExactly(new UUID(0, 51).toString(), new UUID(0, 100).toString());
        assertThat(next.has("nextCursor")).isFalse();
        tool(READ, "list_findings", with("headSha", NEXT_HEAD), "STALE_HEAD");
    }

    @Test
    void listsRetainedInlineFindingsOnceWithTheirCurrentOccurrence() throws Exception {
        UUID older = cycle(101, 7, NEXT_HEAD);
        jdbc.update("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha, source_provider,
                    source_kind, source_id, thread_id, thread_resolved)
                VALUES (?, ?, 101, 1001, ?, 'fixture', 'REVIEW_COMMENT', 'legacy-inline', 'legacy-thread', false)
                """, new UUID(0, 200), older, NEXT_HEAD);

        Instant observedAt = Instant.parse("2026-11-27T12:00:00Z");
        for (int round = 0; round < 2; round++) {
            UUID roundCycle = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage,
                        review_provider, iteration)
                    VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'fixture', ?)
                    """, roundCycle, HEAD, round + 1);
            var comment = new ReviewEvidence.Source("fixture-inline", ReviewEvidence.Kind.REVIEW_COMMENT, null,
                    "fixture-thread", null, null, HEAD, HEAD, "b".repeat(64), observedAt, null, round == 1, false,
                    "src/Example.java", 10, 10, ReviewEvidence.Severity.UNKNOWN);
            evidence.ingest(new ReviewEvidence.Snapshot(UUID.randomUUID(), roundCycle, PR, "fixture", HEAD,
                    observedAt.plusSeconds(round), List.of(comment)));
        }
        assertEquals(2, count("SELECT count(*) FROM findings WHERE stable_finding_id IS NOT NULL"));

        var findings = tool(READ, "list_findings", TARGET, "OK").path("findings").values();
        assertThat(findings).hasSize(2);
        assertThat(findings).anyMatch(finding -> finding.path("id").asText().equals(new UUID(0, 200).toString())
                && finding.path("status").asText().equals("OPEN"));
        assertThat(findings).filteredOn(finding -> !finding.path("stableId").isNull()).singleElement()
                .satisfies(finding -> assertThat(finding.path("threadResolved").booleanValue()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void showsCurrentDispositionWhenTheSameShaIsObservedAgain(boolean confirmSameHead) throws Exception {
        Instant observedAt = Instant.parse("2026-11-27T12:00:00Z");
        var source = new ReviewEvidence.Source("fixture-comment", ReviewEvidence.Kind.ISSUE_COMMENT,
                null, null, null, null, null, null, "a".repeat(64), observedAt, null,
                null, null, null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.ACTIONABLE);
        var snapshot = new ReviewEvidence.Snapshot(UUID.randomUUID(), cycle(101, 7, HEAD), PR, "fixture", HEAD,
                observedAt, List.of(source));
        evidence.ingest(snapshot);
        var stableId = jdbc.queryForObject("SELECT stable_finding_id FROM findings", UUID.class);
        dispositions.dispose(PR, new GeneralFeedback.Disposition(UUID.randomUUID(), stableId, snapshot.id(),
                HEAD, 0, GeneralFeedback.Action.ACKNOWLEDGE, "fixture-maintainer", "Acknowledged", null));
        assertEquals("ACKNOWLEDGED", tool(READ, "list_findings", TARGET, "OK").at("/findings/0/status").asText());
        dismiss(stableId, snapshot.id(), 0);
        assertEquals("DISMISSED", tool(READ, "list_findings", TARGET, "OK").at("/findings/0/status").asText());

        if (confirmSameHead) {
            lifecycle.apply(PR, transition(UUID.randomUUID(), 0, ReviewTransition.Action.CONFIRM_HEAD, null));
        } else {
            lifecycle.apply(PR, transition(UUID.randomUUID(), 0, ReviewTransition.Action.UPDATE_HEAD, NEXT_HEAD));
            lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), 1, NEXT_HEAD, ReviewTransition.Action.UPDATE_HEAD,
                    HEAD, new ReviewTransition.Gates(false, false, false, false, false, ReviewTransition.Ci.UNKNOWN)));
        }
        assertEquals("DISMISSED", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertEquals("OPEN", tool(READ, "list_findings", TARGET, "OK").at("/findings/0/status").asText());

        dismiss(stableId, snapshot.id(), lifecycle.find(PR).orElseThrow().version());
        assertEquals("DISMISSED", tool(READ, "list_findings", TARGET, "OK").at("/findings/0/status").asText());
    }

    @Test
    void deniesUnknownExpiredRevokedAndCrossScopeGrants() throws Exception {
        String status = call("get_pr_status", TARGET);
        assertEquals(401, post(null, status).statusCode());
        assertEquals(401, post("f".repeat(64), status).statusCode());
        assertEquals(401, post(READ, call("get_pr_status", with("installationId", 202))).statusCode());
        assertEquals(401, post(READ, call("get_pr_status", with("repositoryId", 2002))).statusCode());
        assertEquals(401, post(READ, call("get_pr_status", with("prNumber", 8))).statusCode());

        jdbc.update("""
                UPDATE mcp_tool_sessions SET issued_at = clock_timestamp() - interval '2 minutes',
                    expires_at = clock_timestamp() - interval '1 minute' WHERE token_sha256 = ?
                """, digest(READ));
        assertEquals(401, post(READ, status).statusCode());
        jdbc.update("""
                UPDATE mcp_tool_sessions SET expires_at = clock_timestamp() + interval '1 minute',
                    revoked_at = clock_timestamp() WHERE token_sha256 = ?
                """, digest(READ));
        assertEquals(401, post(READ, status).statusCode());
        jdbc.update("UPDATE mcp_tool_sessions SET revoked_at = NULL WHERE token_sha256 = ?", digest(READ));
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101 AND repository_id = 1001");
        assertEquals(401, post(READ, status).statusCode());
        jdbc.update("UPDATE installation_repositories SET enabled = true WHERE installation_id = 101 AND repository_id = 1001");
        jdbc.update("UPDATE installations SET status = 'SUSPENDED' WHERE installation_id = 101");
        assertEquals(401, post(READ, status).statusCode());
        assertEquals(0, auditCount());
    }

    @Test
    void deniesPauseWhenTheGrantExpiresWhileWaitingForThePrLock() throws Exception {
        UUID id = UUID.randomUUID();
        grant(PAUSE, "pause_cycle", id, 0L);
        String tokenDigest = digest(PAUSE);
        CompletableFuture<HttpResponse<String>> pending;
        try (var holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            var lockJdbc = new JdbcTemplate(new SingleConnectionDataSource(holder, true));
            try {
                PostgresLocks.lockPr(lockJdbc, PR);
                int blocker = lockJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                jdbc.update("""
                        UPDATE mcp_tool_sessions SET expires_at = clock_timestamp() + interval '2 seconds'
                        WHERE token_sha256 = ?
                        """, tokenDigest);
                pending = CLIENT.sendAsync(request(PAUSE, call("pause_cycle", command(id, 0))).build(),
                        HttpResponse.BodyHandlers.ofString());
                awaitPrLockWait(blocker);
                assertTrue(grantIsUnexpired(tokenDigest), "The grant must still be valid when the request blocks");
                // Wait on the database clock, leaving time inside the store's three-second transaction deadline.
                jdbc.query("""
                        SELECT pg_sleep(greatest(0, extract(epoch FROM expires_at - clock_timestamp()))::double precision)
                        FROM mcp_tool_sessions WHERE token_sha256 = ?
                        """, rs -> {}, tokenDigest);
                assertFalse(grantIsUnexpired(tokenDigest));
            } finally {
                holder.rollback();
            }
        }
        var response = pending.get(5, TimeUnit.SECONDS);
        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, transitionCount());
        assertEquals(0, auditCount());
        assertEquals(0, count("SELECT count(*) FROM pr_domain_events"));
        assertEquals(PullRequestReview.initial(PR, HEAD), lifecycle.find(PR).orElseThrow());
    }

    @Test
    void deniesMissingOrForgedToolArgumentsAndUnactionableRpcMessages() throws Exception {
        var command = UUID.randomUUID();
        grant(PAUSE, "pause_cycle", command, 0L);
        var args = command(command, 0);
        var noHead = new HashMap<>(args);
        noHead.remove("headSha");
        var withSpoofedActor = new HashMap<>(args);
        withSpoofedActor.put("actorId", 42);
        assertInvalidParams(call("pause_cycle", noHead));
        assertInvalidParams(call("pause_cycle", withSpoofedActor));
        assertInvalidParams(call("get_pr_status", with("installationId", "101")));
        assertInvalidParams(call("get_pr_status", with("prNumber", Long.MAX_VALUE)));
        assertInvalidParams(call("get_pr_status", with("headSha", "a".repeat(8))));
        assertInvalidParams(call("list_findings", with("after", "invalid")));
        assertEquals(400, post(PAUSE, call("pause_cycle", args).replace("\"id\":\"fixture\",", "")
                .replace(",\"id\":\"fixture\"", "")).statusCode());
        assertEquals(400, post(PAUSE, "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\"}").statusCode());
        assertEquals(413, post(PAUSE, " ".repeat(16_385)).statusCode());
        assertEquals(403, CLIENT.send(request(PAUSE, call("pause_cycle", args))
                .header("Origin", "https://fixture.invalid").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, auditCount());
        assertEquals(0, transitionCount());
    }

    @Test
    void bindsTheApprovalToTheExactActionCommandHeadAndVersion() throws Exception {
        var pause = UUID.randomUUID();
        var review = UUID.randomUUID();
        grant(PAUSE, "pause_cycle", pause, 0L);
        grant(REVIEW, "request_review", review, 0L);

        tool(READ, "pause_cycle", command(pause, 0), "APPROVAL_REQUIRED");
        tool(PAUSE, "request_review", command(pause, 0), "APPROVAL_REQUIRED");
        tool(PAUSE, "pause_cycle", command(UUID.randomUUID(), 0), "APPROVAL_REQUIRED");
        tool(PAUSE, "pause_cycle", command(pause, 1), "APPROVAL_REQUIRED");
        tool(REVIEW, "request_review", command(review, 0), "PROVIDER_UNAVAILABLE");
        assertEquals(0, count("SELECT count(*) FROM outbox_commands"));
        assertEquals(0, count("SELECT count(*) FROM review_requests"));
        assertEquals(0, transitionCount());

        lifecycle.apply(PR, transition(UUID.randomUUID(), 0, ReviewTransition.Action.CONFIRM_HEAD, null));
        tool(PAUSE, "pause_cycle", command(pause, 0), "STALE_VERSION");
        lifecycle.apply(PR, transition(UUID.randomUUID(), 1, ReviewTransition.Action.UPDATE_HEAD, NEXT_HEAD));
        tool(PAUSE, "pause_cycle", command(pause, 0), "STALE_HEAD");
        assertEquals(2, transitionCount());
        assertEquals(7, auditCount());
    }

    @Test
    void concurrentPauseRetriesApplyOnceAndAuditTheAuthorizedActor() throws Exception {
        var id = UUID.randomUUID();
        grant(PAUSE, "pause_cycle", id, 0L);
        String payload = call("pause_cycle", command(id, 0));
        var requests = IntStream.range(0, 4).mapToObj(index -> CLIENT.sendAsync(request(PAUSE, payload).build(),
                HttpResponse.BodyHandlers.ofString())).toList();
        var responses = requests.stream().map(CompletableFuture::join).map(this::structured).toList();
        assertThat(responses).extracting(result -> result.path("code").asText())
                .containsExactlyInAnyOrder("APPLIED", "ALREADY_APPLIED", "ALREADY_APPLIED", "ALREADY_APPLIED");
        assertThat(responses).allMatch(result -> result.path("appliedVersion").longValue() == 1);
        assertEquals(1, transitionCount());
        assertEquals("PAUSED", lifecycle.find(PR).orElseThrow().state().name());
        assertEquals(List.of("mcp-user:42"), jdbc.queryForList("SELECT DISTINCT actor FROM audit_entries", String.class));
        assertEquals(1, count("SELECT count(*) FROM audit_entries WHERE outcome = 'APPLIED'"));
        assertEquals(3, count("SELECT count(*) FROM audit_entries WHERE outcome = 'ALREADY_APPLIED'"));
        assertEquals(1, count("SELECT count(*) FROM pr_domain_events"));
    }

    @Test
    void auditFailureRollsBackStateAndEvent() throws Exception {
        var id = UUID.randomUUID();
        grant(PAUSE, "pause_cycle", id, 0L);
        jdbc.execute("ALTER TABLE audit_entries ADD CONSTRAINT fixture_no_pause CHECK (action <> 'mcp.pause_cycle')");
        try {
            var failure = post(PAUSE, call("pause_cycle", command(id, 0)));
            assertEquals(503, failure.statusCode());
            assertThat(failure.body()).isEmpty();
            assertEquals(0, transitionCount());
            assertEquals(0, count("SELECT count(*) FROM pr_domain_events"));
        } finally {
            jdbc.execute("ALTER TABLE audit_entries DROP CONSTRAINT fixture_no_pause");
        }
        tool(PAUSE, "pause_cycle", command(id, 0), "APPLIED");
    }

    private void assertInvalidParams(String body) throws Exception {
        var response = post(PAUSE, body);
        assertEquals(200, response.statusCode());
        assertEquals(-32602, JSON.readTree(response.body()).at("/error/code").intValue());
    }

    private ReviewEvidence.Source inlineSource(String bodyHash, Instant updatedAt, boolean resolved) {
        return new ReviewEvidence.Source("fixture-inline", ReviewEvidence.Kind.REVIEW_COMMENT, null,
                "fixture-thread", null, null, HEAD, HEAD, bodyHash, updatedAt, null, resolved, false,
                "src/Example.java", 10, 10, ReviewEvidence.Severity.UNKNOWN);
    }

    private void assertInlineFinding(UUID stableId, String status, boolean resolved) throws Exception {
        assertThat(tool(READ, "list_findings", TARGET, "OK").path("findings").values()).singleElement()
                .satisfies(row -> {
                    assertEquals(stableId.toString(), row.path("stableId").asText());
                    assertEquals(status, row.path("status").asText());
                    assertEquals(resolved, row.path("threadResolved").booleanValue());
                });
    }

    private void awaitPrLockWait(int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                        AND wait_event = 'advisory' AND ? = ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, blocker))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Pause request did not reach the PostgreSQL advisory lock");
    }

    private boolean grantIsUnexpired(String tokenDigest) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT expires_at > clock_timestamp() FROM mcp_tool_sessions WHERE token_sha256 = ?
                """, Boolean.class, tokenDigest));
    }

    private void dismiss(UUID stableId, UUID snapshotId, long version) {
        dispositions.dispose(PR, new GeneralFeedback.Disposition(UUID.randomUUID(), stableId, snapshotId,
                HEAD, version, GeneralFeedback.Action.DISMISS, "fixture-maintainer", "Confirmed dismissal", null));
    }

    private ReviewTransition transition(UUID id, long version, ReviewTransition.Action action, String newHead) {
        return new ReviewTransition(id, version, HEAD, action, newHead,
                new ReviewTransition.Gates(false, false, false, false, false, ReviewTransition.Ci.UNKNOWN));
    }

    private void grant(String token, String tool, UUID id, Long version) {
        jdbc.update("""
                INSERT INTO mcp_tool_sessions (token_sha256, actor_id, installation_id, repository_id, pr_number,
                    expires_at, approved_tool, command_id, approved_head_sha, approved_version)
                VALUES (?, 42, 101, 1001, 7, clock_timestamp() + interval '5 minutes', ?, ?, ?, ?)
                """, digest(token), tool, id, id == null ? null : HEAD, version);
    }

    private String digest(String token) {
        return Sha256.hex(token.getBytes(StandardCharsets.US_ASCII));
    }

    private UUID cycle(long installation, int prNumber, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                VALUES (?, ?, 1001, ?, ?, 'BASELINE', 'fixture')
                """, id, installation, prNumber, sha);
        return id;
    }

    private void finding(UUID cycle, long installation, String sha, long index) {
        jdbc.update("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha, source_provider,
                    source_kind, source_id, body_ref)
                VALUES (?, ?, ?, 1001, ?, 'fixture', 'ISSUE_COMMENT', ?, 'private-body-reference')
                """, new UUID(0, index), cycle, installation, sha, Long.toString(index));
    }

    private int auditCount() { return count("SELECT count(*) FROM audit_entries"); }
    private int transitionCount() { return count("SELECT count(*) FROM pr_review_transitions"); }
    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private Map<String, Object> with(String key, Object value) {
        var result = new HashMap<>(TARGET);
        result.put(key, value);
        return result;
    }

    private Map<String, Object> command(UUID id, long version) {
        var result = with("commandId", id);
        result.put("expectedVersion", version);
        return result;
    }

    private String rpc(String method, Map<String, ?> params) {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "fixture", "method", method, "params", params));
    }

    private String call(String tool, Map<String, ?> args) {
        return rpc("tools/call", Map.of("name", tool, "arguments", args));
    }

    private JsonNode tool(String token, String tool, Map<String, ?> args, String code) throws Exception {
        var body = structured(post(token, call(tool, args)));
        assertEquals(code, body.path("code").asText());
        return body;
    }

    private JsonNode structured(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        var json = JSON.readTree(response.body());
        assertEquals("fixture", json.path("id").asText());
        var result = json.path("result");
        var content = result.path("structuredContent");
        assertEquals(content, JSON.readTree(result.at("/content/0/text").asText()));
        return content;
    }

    private URI endpoint() {
        return URI.create("http://localhost:" + port + "/mcp");
    }

    private HttpRequest.Builder request(String token, String body) {
        return request(token, body, VERSION);
    }

    private HttpRequest.Builder request(String token, String body, String version) {
        var request = HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (version != null) request.header("MCP-Protocol-Version", version);
        return request;
    }

    private HttpResponse<String> initialize(String requestedVersion) throws Exception {
        return post(READ, rpc("initialize", Map.of("protocolVersion", requestedVersion, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "fixture", "version", "1"))), null);
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        return post(token, body, VERSION);
    }

    private HttpResponse<String> post(String token, String body, String version) throws Exception {
        return CLIENT.send(request(token, body, version).build(), HttpResponse.BodyHandlers.ofString());
    }
}
