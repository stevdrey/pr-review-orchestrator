package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.PrReviewOrchestratorApplication;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcAuthorNoticeStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcFixCorrelationStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxReviewPlanning;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewRequestStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewOutcomeStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewEvidenceStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcRepositoryPolicy;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCiInbox;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCodexBudget;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCodexBudget.Terms;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxReviewInbox;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcWorkQueue;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.RepositoryPolicyFixtures;
import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.CodexApproval;
import io.github.stevdrey.prrevieworchestrator.ports.CodexCompletionSource.TerminalAttempt;
import io.github.stevdrey.prrevieworchestrator.ports.CommandGateway;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewIntentPolicy;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials.Credential;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider.Result;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore.Receipt;
import io.github.stevdrey.prrevieworchestrator.ports.SandboxInlineFixStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(classes = PrReviewOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"orchestrator.github.enabled=false", "orchestrator.github.webhook.enabled=true",
                "orchestrator.dispatch.enabled=false", "orchestrator.codex.enabled=false",
                "orchestrator.notifications.enabled=false", "orchestrator.notifications.bot-login=fixture-app[bot]"})
class FeedbackLoopSandboxIntegrationTest {
    private static final String SECRET = "sandbox-webhook-signing-fixture-only";
    private static final Path SECRET_FILE = fixtureSecret();
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    private static final ReviewTransition.Gates OPEN = new ReviewTransition.Gates(true, false, false, true, false, Ci.UNKNOWN);
    private static final ReviewTransition.Gates BLOCKED = new ReviewTransition.Gates(true, false, false, true, true, Ci.UNKNOWN);
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
    private static final Terms BUDGET_TERMS = new Terms("fixture_units", 2, 2, 8, 16);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("orchestrator.github.webhook.secret-file", SECRET_FILE::toString);
        registry.add("orchestrator.github.repositories.101[0]", () -> "1001");
    }

    @Test
    void retainedInlineThreadStaysInOriginalRoundAcrossFixAndEdits() throws Exception {
        lifecycle.create(PR, repository.openedSha);
        reviewEvidence(false, "original inline", repository.openedSha);
        assertEquals(202, signed("pull_request_review", UUID.randomUUID(),
                reviewWebhook("submitted", "review", repository.openedSha, 7)).statusCode());
        assertTrue(reviewWorker().processEvent());
        var finding = jdbc.queryForObject("SELECT stable_finding_id FROM findings", UUID.class);

        var update = push(repository.fixedSha);
        assertTrue(fixes.observeSynchronize(update, current(), head()));
        complete(update);
        assertTrue(repository.verifiesFix(repository.openedSha, github.head()));
        var worker = reviewWorker(repository.fixedSha);
        reviewEvidence(true, "original inline", repository.openedSha);
        var delivery = UUID.randomUUID();
        String edited = reviewWebhook("edited", "comment", repository.fixedSha, 7);
        assertEquals(202, signed("pull_request_review_comment", delivery, edited).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(200, signed("pull_request_review_comment", delivery, edited).statusCode());
        assertFalse(worker.processEvent());
        var history = jdbc.queryForMap("""
                SELECT f.evidence_snapshot_id, f.reviewed_sha, o.observed_head_sha, s.thread_resolved
                FROM findings f JOIN review_evidence_snapshots o ON o.id = f.evidence_snapshot_id
                JOIN review_evidence_sources s ON s.snapshot_id = o.id AND s.source_id = f.source_id
                """);
        assertEquals(repository.openedSha, history.get("reviewed_sha"));
        assertEquals(repository.fixedSha, history.get("observed_head_sha"));
        assertEquals(true, history.get("thread_resolved"));
        assertEquals(3, count("review_evidence_snapshots"));

        var verifier = new VerifySandboxInlineFix(PR, repository.fixedSha, authorization, inlineFixes);
        assertTrue(verifier.verify(new SandboxInlineFix(finding, (UUID) history.get("evidence_snapshot_id"),
                current().version(), repository.openedSha, repository.fixedSha, "Example.java",
                repository.blob(repository.openedSha), repository.blob(repository.fixedSha),
                "fixture-verifier", "The committed source now returns the expected value.")));
        assertEquals(202, signed("pull_request_review_comment", UUID.randomUUID(),
                reviewWebhook("created", "comment", repository.fixedSha, 7)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals("VERIFIED_FIXED", jdbc.queryForObject("SELECT status FROM findings", String.class));

        reviewEvidence(false, "edited inline", repository.openedSha);
        assertEquals(202, signed("pull_request_review_comment", UUID.randomUUID(),
                reviewWebhook("edited", "comment", repository.fixedSha, 7)
                        .replace("\"comment\":{\"id\":1}", "\"comment\":{\"id\":1,\"updated_at\":\"2026-11-19T12:01:00Z\"}"))
                .statusCode());
        assertTrue(worker.processEvent());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertEquals(finding, jdbc.queryForObject("SELECT stable_finding_id FROM findings", UUID.class));
        assertThat(github.comments()).isEmpty();
    }

    @LocalServerPort int port;
    @TempDir Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RepositoryAuthorization authorization;
    @Autowired ReviewLifecycle lifecycle;
    @Autowired CorrelateFixes fixes;
    @Autowired JdbcFixCorrelationStore fixStore;
    @Autowired DisposeGeneralFeedback feedback;
    @Autowired IngestReviewEvidence evidence;
    @Autowired AssessReviewOutcome outcomes;
    @Autowired GitHubReviewNormalizer normalizer;
    @Autowired JdbcReviewStateStore states;
    @Autowired JdbcReviewEvidenceStore evidenceStore;
    @Autowired SandboxInlineFixStore inlineFixes;

    private JdbcWorkQueue queue;
    private JdbcReviewRequestStore requests;
    private SandboxRepository repository;
    private SandboxGitHub github;
    private NotifyAuthor notices;
    private GitHubInstallationTokens tokens;
    private HttpClient webhookClient;
    private final Clock clock = Clock.systemUTC();
    private int nextCheckRunId = 1;

    @BeforeEach
    void openSandbox() throws Exception {
        jdbc.execute("TRUNCATE installations, inbox_events, sandbox_codex_budget_limits CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true)");
        RepositoryPolicyFixtures.allowReviews(jdbc, "codex", "sandbox-deep");
        var limits = new DurableWork.Limits(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(10));
        queue = new JdbcWorkQueue(jdbc, transactions, limits);
        requests = new JdbcReviewRequestStore(jdbc, transactions, states);
        repository = new SandboxRepository(directory);
        github = new SandboxGitHub(repository.openedSha, clock.instant().plus(TOKEN_LIFETIME));
        github.trees(Map.of(repository.openedSha, repository.tree(repository.openedSha),
                repository.fixedSha, repository.tree(repository.fixedSha),
                repository.followUpSha, repository.tree(repository.followUpSha)));
        webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        Path key = directory.resolve("fixture-app.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        tokens = new GitHubInstallationTokens(authorization, new GitHubAppJwt("Iv1.sandbox", key, clock), clock, github.uri());
        notices = new NotifyAuthor(new JdbcAuthorNoticeStore(jdbc, states, transactions),
                new GitHubAuthorNotices(tokens, clock, SandboxGitHub.BOT, github.uri()), authorization);
    }

    @AfterEach
    void closeSandbox() {
        if (webhookClient != null) webhookClient.close();
        if (github != null) {
            github.close();
            assertThat(github.unexpectedRequests()).isEmpty();
        }
    }

    @AfterAll
    static void deleteSigningFixture() throws IOException {
        Files.deleteIfExists(SECRET_FILE);
    }

    @Test
    void durableWorkerReconcilesSignedCheckAndWorkflowEventsAcrossAttemptsAtOneSha() throws Exception {
        var worker = ciWorker();
        github.workflowRuns(List.of(workflow(21, 1, 1, "completed", "success"),
                workflow(22, 1, 1, "completed", "success"), workflow(22, 2, 1, "in_progress", "")));
        var check = UUID.randomUUID();
        var checkPayload = ciWebhook("check_run", repository.openedSha, 10);
        assertEquals(202, signed("check_run", check, checkPayload).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(Ci.PENDING, current().ci());

        github.workflowRuns(List.of(workflow(21, 1, 1, "completed", "success"),
                workflow(22, 2, 2, "completed", "success")));
        var workflow = UUID.randomUUID();
        var workflowPayload = ciWebhook("workflow_run", repository.openedSha, 11);
        assertEquals(202, signed("workflow_run", workflow, workflowPayload).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(Ci.PASSED, current().ci());
        assertEquals(200, signed("check_run", check, checkPayload).statusCode());
        assertEquals(200, signed("workflow_run", workflow, workflowPayload).statusCode());
        assertFalse(worker.processEvent());
        assertEquals(202, signed("check_run", UUID.randomUUID(), ciWebhook("check_run", repository.fixedSha, 12)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(4, github.actionReads());
        assertEquals(2, count("pr_review_transitions"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE status='PROCESSED'", Integer.class));
        assertEquals(Ci.PASSED, current().ci());
        assertFalse(worker.processCommand());
        assertEquals(0, count("outbox_commands"));
        assertEquals(0, count("review_requests"));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void workerRetriesFromFreshEvidenceWhenInboxCommitRollsBackCi() throws Exception {
        var worker = ciWorker();
        github.workflowRuns(List.of(workflow(21, 1, 1, "completed", "success"),
                workflow(22, 1, 1, "completed", "success")));
        assertEquals(202, signed("workflow_run", UUID.randomUUID(),
                ciWebhook("workflow_run", repository.openedSha, 13)).statusCode());
        jdbc.execute("""
                CREATE FUNCTION reject_sandbox_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.outcome = 'PROCESSED' THEN RAISE EXCEPTION 'sandbox commit failure'; END IF;
                RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_sandbox_commit AFTER INSERT ON queue_transitions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_sandbox_commit()
                """);
        try {
            assertTrue(worker.processEvent());
            assertEquals(0, count("pr_review_transitions"));
            assertTrue(lifecycle.find(PR).isEmpty());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM inbox_events", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_sandbox_commit ON queue_transitions");
            jdbc.execute("DROP FUNCTION reject_sandbox_commit()");
        }
        github.workflowRuns(List.of(workflow(21, 1, 2, "completed", "failure"),
                workflow(22, 1, 1, "completed", "success")));
        jdbc.execute("UPDATE inbox_events SET available_at=clock_timestamp()");
        assertTrue(ciWorker().processEvent());
        assertEquals(Ci.FAILED, current().ci());
        assertEquals(1, count("pr_review_transitions"));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM inbox_events WHERE status='PROCESSED'", Integer.class));
        assertEquals(0, count("outbox_commands"));
    }

    @Test
    void signedPushesCoalesceAndConcurrentCiEventsPlanOneIntentForTheLatestHead() throws Exception {
        var round = startRemediation();
        var worker = planningWorker((pr, intent) -> true, 6);
        String first = repository.fixedSha;
        String latest = repository.followUpSha;
        check(repository.openedSha, Ci.PASSED);
        github.push(first);
        var delivery = UUID.randomUUID();
        String push = pullWebhook("synchronize", first);
        assertEquals(202, signed("pull_request", delivery, push).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(first, current().headSha());
        assertEquals(Ci.UNKNOWN, current().ci());
        assertEquals(200, signed("pull_request", delivery, push).statusCode());
        assertFalse(worker.processEvent(), "assessment is durably deferred during the quiet period");

        github.push(latest);
        String latestPush = pullWebhook("synchronize", latest);
        assertEquals(202, signed("pull_request", UUID.randomUUID(), latestPush).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(latest, current().headSha());
        var current = current();
        String delayed = prEnvelope("synchronize", first, ",\"number\":7,\"before\":\""
                + repository.openedSha + "\"");
        assertEquals(202, signed("pull_request", UUID.randomUUID(), delayed).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(current, current());
        assertEquals(0, count("outbox_commands"));

        dispose(round, GeneralFeedback.Action.CLAIM_FIXED, latest);
        successfulWorkflows();
        jdbc.update("UPDATE inbox_events SET available_at=clock_timestamp() WHERE status='PENDING' AND head_sha=?", first);
        assertTrue(worker.processEvent(), "the superseded assessment completes without comparing trees or planning");
        assertEquals(0, count("outbox_commands"));
        agePlanning();
        assertEquals(202, signed("workflow_run", UUID.randomUUID(), ciWebhook("workflow_run", latest, 30)).statusCode());
        // The due synchronize delivery and the CI event target the same SHA on independent workers.
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(worker::processEvent);
            var two = workers.submit(planningWorker((pr, intent) -> true, 6)::processEvent);
            assertTrue(one.get(20, TimeUnit.SECONDS));
            assertTrue(two.get(20, TimeUnit.SECONDS));
        }
        drainPlanning(worker);
        assertEquals(Ci.PASSED, current().ci());
        assertEquals(1, count("outbox_commands"));
        assertEquals(1, count("fix_review_candidates"));
        assertEquals(1, count("review_requests"), "only the initial fixture reservation exists");
        assertEquals(Map.of("expected_head_sha", latest, "stage", "BASELINE",
                "target_provider", "codex", "command_type", "REQUEST_REVIEW"), jdbc.queryForMap("""
                SELECT o.expected_head_sha, c.stage, o.target_provider, o.command_type
                FROM outbox_commands o JOIN review_cycles c ON c.id = o.review_cycle_id
                """));
        assertFalse(worker.processCommand(), "the sandbox planner never dispatches");

        var approvals = new FixtureBudget(1);
        var dispatch = plannedDispatch(approvals);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(dispatch::processCommand);
            var two = workers.submit(plannedDispatch(approvals)::processCommand);
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertEquals(REVIEWING, current().state());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        replayPlannedCommand();
        assertTrue(plannedDispatch(new FixtureBudget(0)).processCommand());
        assertFalse(dispatch.processCommand());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertThat(github.comments()).containsExactly(new SandboxGitHub.Comment(9001, latest, "@codex review"));
        assertEquals(2, count("review_requests"));
        assertEquals(Map.of("head_sha", latest, "stage", "BASELINE", "comment_id", 9001L, "head_epoch", 0L,
                        "state", "COMMENT_CREATED"), jdbc.queryForMap("""
                SELECT r.head_sha, r.stage, r.comment_id, r.head_epoch, r.state
                FROM review_requests r JOIN outbox_commands o ON o.id = r.planned_command_id
                """));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM pr_review_transitions t JOIN review_requests r ON r.id = t.transition_id
                WHERE t.action = 'START_REVIEW' AND r.planned_command_id IS NOT NULL
                """, Integer.class));
    }

    @Test
    void plannedReceiptReconcilesAfterConcurrentPauseAndResume() throws Exception {
        planCorrectiveReview();
        dispatchAndPauseAfterReceipt();
        var paused = current();
        assertEquals(PAUSED, paused.state());
        assertEquals(WAITING_FOR_REVIEW, paused.resumeState());
        UUID request = assertPlannedReceipt();
        assertEquals(0, plannedStarts(request));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertSingleBudgetHold(repository.fixedSha);

        var resume = transition(RESUME, OPEN);
        assertEquals(REVIEWING, lifecycle.apply(PR, resume).state());
        assertEquals(1, plannedStarts(request));
        assertEquals(ReviewConflict.Code.DUPLICATE_TRANSITION,
                assertThrows(ReviewConflict.class, () -> lifecycle.apply(PR, resume)).code());
        replayPlannedCommand();
        assertTrue(plannedDispatch(new FixtureBudget(0), budgetedRequests()).processCommand());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals(REVIEWING, current().state());
        assertEquals(request, assertPlannedReceipt());
        assertEquals(1, plannedStarts(request));
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedReceiptFromEarlierVisitDoesNotStartAfterResume() throws Exception {
        planCorrectiveReview();
        dispatchAndPauseAfterReceipt();
        assertEquals(PAUSED, current().state());
        UUID request = assertPlannedReceipt();

        var sameHead = enqueue("pull_request", "synchronize", repository.fixedSha,
                prEnvelope("synchronize", repository.fixedSha, ",\"number\":7,\"before\":\"" + repository.fixedSha + "\""));
        assertFalse(fixes.observeSynchronize(sameHead, current(), head()));
        complete(sameHead);
        assertEquals(PAUSED, current().state());
        assertEquals(WAITING_FOR_REVIEW, lifecycle.apply(PR, transition(RESUME, OPEN)).state());
        assertEquals(0, plannedStarts(request));
        assertEquals(request, assertPlannedReceipt());
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedReceiptSurvivesStartReviewCommitFailure() throws Exception {
        planCorrectiveReview();
        jdbc.execute("""
                CREATE FUNCTION reject_planned_start() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.action = 'START_REVIEW' THEN RAISE EXCEPTION 'fixture commit failure'; END IF;
                RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_planned_start AFTER INSERT ON pr_review_transitions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_planned_start()
                """);
        try {
            assertTrue(plannedDispatch(new FixtureBudget(1), budgetedRequests()).processCommand());
        } finally {
            jdbc.execute("DROP TRIGGER reject_planned_start ON pr_review_transitions");
            jdbc.execute("DROP FUNCTION reject_planned_start()");
        }
        UUID request = assertPlannedReceipt();
        assertEquals(WAITING_FOR_REVIEW, current().state());
        assertEquals(0, plannedStarts(request));
        assertEquals(Map.of("status", "PENDING", "last_error_code", "INTERNAL_ERROR"),
                jdbc.queryForMap("SELECT status, last_error_code FROM outbox_commands"));
        assertSingleBudgetHold(repository.fixedSha);

        jdbc.update("UPDATE outbox_commands SET available_at = clock_timestamp()");
        assertTrue(plannedDispatch(new FixtureBudget(0), budgetedRequests()).processCommand());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals(REVIEWING, current().state());
        assertEquals(request, assertPlannedReceipt());
        assertEquals(1, plannedStarts(request));
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedReceiptSurvivesOutboxCommitFailureAndReconcilesWithoutAnotherPost() throws Exception {
        planCorrectiveReview();
        jdbc.execute("""
                CREATE FUNCTION reject_planned_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.outcome = 'SUCCEEDED' THEN RAISE EXCEPTION 'fixture commit failure'; END IF;
                RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_planned_completion AFTER INSERT ON queue_transitions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_planned_completion()
                """);
        try {
            assertTrue(plannedDispatch(new FixtureBudget(1), budgetedRequests()).processCommand());
        } finally {
            jdbc.execute("DROP TRIGGER reject_planned_completion ON queue_transitions");
            jdbc.execute("DROP FUNCTION reject_planned_completion()");
        }
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals("REMOTE_UNCERTAIN", jdbc.queryForObject("SELECT last_error_code FROM outbox_commands", String.class));
        assertEquals(9001L, jdbc.queryForObject("""
                SELECT comment_id FROM review_requests WHERE planned_command_id IS NOT NULL
                """, Long.class));
        assertEquals(REVIEWING, current().state());
        assertSingleBudgetHold(repository.fixedSha);
        jdbc.update("UPDATE outbox_commands SET available_at = clock_timestamp()");
        assertTrue(plannedDispatch(new FixtureBudget(0), budgetedRequests()).processCommand());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertPlannedReceipt();
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedDispatchChecksRemoteHeadAndNeverReusesAnUnsentPermit() throws Exception {
        planCorrectiveReview();
        github.push(repository.followUpSha); // GitHub is already ahead of the local journal.
        var dispatch = plannedDispatch(new FixtureBudget(1));
        assertTrue(dispatch.processCommand());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals("NOT_SENT", jdbc.queryForObject("""
                SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        github.push(repository.fixedSha);
        replayPlannedCommand();
        assertTrue(dispatch.processCommand());
        assertThat(github.comments()).isEmpty();
        assertEquals(WAITING_FOR_REVIEW, current().state());
    }

    @Test
    void plannedDispatchRejectsAnEarlierVisitEvenAfterReturningToItsSha() throws Exception {
        planCorrectiveReview();
        advance(repository.followUpSha);
        github.push(repository.fixedSha);
        var returned = enqueue("pull_request", "synchronize", repository.fixedSha,
                prEnvelope("synchronize", repository.fixedSha, ",\"number\":7,\"before\":\"" + repository.followUpSha + "\""));
        assertTrue(fixes.observeSynchronize(returned, current(), head()));
        complete(returned);
        check(repository.fixedSha, Ci.PASSED);
        assertTrue(plannedDispatch(new FixtureBudget(1)).processCommand());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals(1, count("review_requests"));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void plannedDispatchRejectsPolicyRevokedAfterClaimAndBeforePost() throws Exception {
        planCorrectiveReview();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var dispatch = plannedDispatch(request -> {
            if (checks.incrementAndGet() == 4) {
                throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            }
        });
        assertTrue(dispatch.processCommand());
        assertEquals(4, checks.get());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals("NOT_SENT", jdbc.queryForObject("""
                SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void plannedDispatchRejectsRevokedRepositoryWithoutAReservation() throws Exception {
        planCorrectiveReview();
        jdbc.update("UPDATE installation_repositories SET enabled = false");
        assertTrue(plannedDispatch(new FixtureBudget(1)).processCommand());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertNull(jdbc.queryForObject("SELECT last_error_code FROM outbox_commands", String.class));
        assertEquals(1, count("review_requests"));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void plannedDispatchStopsAtExhaustedPersistentBudgetDespiteApproval() throws Exception {
        var store = budgetedRequests();
        planCorrectiveReview(store);
        assertSingleBudgetHold(repository.openedSha);
        var approved = new AtomicBoolean();
        assertTrue(plannedDispatch(request -> approved.set(true), store).processCommand());

        assertTrue(approved.get(), "the approval gate allowed the planned send");
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals(1, count("review_requests"));
        assertEquals(WAITING_FOR_REVIEW, current().state());
        assertThat(github.comments()).isEmpty();
        assertSingleBudgetHold(repository.openedSha);
    }

    @Test
    void plannedDispatchRetriesATransientReadBeforePost() throws Exception {
        planCorrectiveReview();
        github.failNextPullReads(1);
        var dispatch = plannedDispatch(new FixtureBudget(1), budgetedRequests());
        assertTrue(dispatch.processCommand());
        assertEquals(Map.of("status", "PENDING", "last_error_code", "REMOTE_UNAVAILABLE"),
                jdbc.queryForMap("SELECT status, last_error_code FROM outbox_commands"));
        assertEquals("RESERVED", jdbc.queryForObject("""
                SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        assertThat(github.comments()).isEmpty();
        String requestKey = jdbc.queryForObject("""
                SELECT request_key FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class);
        assertSingleBudgetHold(repository.fixedSha);

        jdbc.update("UPDATE outbox_commands SET available_at = clock_timestamp()");
        assertTrue(plannedDispatch(new FixtureBudget(1), budgetedRequests()).processCommand());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals("COMMENT_CREATED", jdbc.queryForObject("""
                SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        assertThat(github.comments()).containsExactly(new SandboxGitHub.Comment(9001, repository.fixedSha, "@codex review"));
        assertEquals(REVIEWING, current().state());
        assertEquals(requestKey, jdbc.queryForObject("""
                SELECT request_key FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        assertEquals(2, count("review_requests"));
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedDispatchRetainsAnAmbiguousPostWithoutRetryingIt() throws Exception {
        planCorrectiveReview();
        github.rejectCommentReceipt();
        var dispatch = plannedDispatch(new FixtureBudget(1), budgetedRequests());
        assertTrue(dispatch.processCommand());
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_commands", String.class));
        assertEquals("REMOTE_UNCERTAIN", jdbc.queryForObject("SELECT last_error_code FROM outbox_commands", String.class));
        assertEquals("ATTEMPTED", jdbc.queryForObject("""
                SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                """, String.class));
        assertNull(jdbc.queryForObject("""
                SELECT comment_id FROM review_requests WHERE planned_command_id IS NOT NULL
                """, Long.class));
        assertSingleBudgetHold(repository.fixedSha);
        replayPlannedCommand();
        assertTrue(plannedDispatch(new FixtureBudget(1), budgetedRequests()).processCommand());
        assertFalse(dispatch.processCommand());
        assertEquals("REMOTE_UNCERTAIN", jdbc.queryForObject("SELECT last_error_code FROM outbox_commands", String.class));
        assertThat(github.comments()).containsExactly(new SandboxGitHub.Comment(9001, repository.fixedSha, "@codex review"));
        assertEquals(WAITING_FOR_REVIEW, current().state());
        assertEquals(2, count("review_requests"));
        assertSingleBudgetHold(repository.fixedSha);
    }

    @Test
    void plannedCompletionRequiresAuthenticatedExactAttemptAndCurrentRevision() throws Exception {
        var initial = planCorrectiveReview();
        assertTrue(plannedDispatch(new FixtureBudget(1)).processCommand());
        verifyFixtureInlineFix(initial);
        dispose(initial, GeneralFeedback.Action.DISMISS, null);
        var run = jdbc.queryForObject("""
                SELECT r.id, r.head_sha, o.review_cycle_id FROM review_requests r
                JOIN outbox_commands o ON o.id = r.planned_command_id
                """, (rs, row) -> new Run(PR, rs.getString("head_sha"), "codex", Stage.BASELINE,
                rs.getObject("id", UUID.class), rs.getObject("review_cycle_id", UUID.class), "github", "fixture-reviewer"));

        var original = ingestCompletion(run, "");
        var signed = new AtomicReference<SignedAttempt>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var source = new AssessReviewOutcome(new JdbcReviewOutcomeStore(jdbc, states), authorization,
                Optional.of((pr, commentId) -> {
                    assertEquals(PR, pr);
                    assertEquals(9001L, commentId);
                    reads.incrementAndGet();
                    return Optional.ofNullable(signed.get())
                            .filter(envelope -> MessageDigest.isEqual(envelope.signature(), signAttempt(envelope.attempt())))
                            .map(SignedAttempt::attempt);
                }));
        long version = current().version();
        assertEquals(Verdict.UNKNOWN, outcomes.assessCodex(run, version, original.id()).verdict());
        var unlinked = new Assessment(run, version, new Completion(run, "R2", Status.COMPLETED, Conclusion.NO_FINDINGS),
                original.id());
        assertEquals(Verdict.UNKNOWN, outcomes.assess(unlinked).verdict());
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> step(BASELINE_CLEAN, OPEN)).code());
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, original.id()).verdict());

        var authenticatedOriginal = attempt(original, 9001, Stage.BASELINE, run.headSha(), "attempt-1");
        signed.set(new SignedAttempt(authenticatedOriginal, new byte[32]));
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, original.id()).verdict());
        signed.set(SignedAttempt.authentic(authenticatedOriginal));
        assertEquals(Verdict.CLEAN_FOR_SHA, source.assessCodex(run, version, original.id()).verdict());

        // Same source ID, different body revision. A previously valid signed record cannot clear the edited source.
        var edited = ingestCompletion(run, "   ");
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, original.id()).verdict());
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, edited.id()).verdict());
        assertEquals(ReviewConflict.Code.GUARD_REJECTED, assertThrows(ReviewConflict.class,
                () -> source.recordCodexClean(run, version, edited.id(), UUID.randomUUID(), OPEN)).code());

        signed.set(SignedAttempt.authentic(attempt(edited, 9001, Stage.DEEP, run.headSha(), "attempt-1")));
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, edited.id()).verdict());
        signed.set(SignedAttempt.authentic(attempt(edited, 9001, Stage.BASELINE, repository.openedSha, "attempt-1")));
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, edited.id()).verdict());
        signed.set(SignedAttempt.authentic(attempt(edited, 9002, Stage.BASELINE, run.headSha(), "attempt-2")));
        assertEquals(Verdict.UNKNOWN, source.assessCodex(run, version, edited.id()).verdict());
        assertTrue(reads.get() > 0);
        assertEquals(0, count("review_clean_evidence"));

        signed.set(SignedAttempt.authentic(attempt(edited, 9001, Stage.BASELINE, run.headSha(), "attempt-1")));
        UUID transition = UUID.randomUUID();
        assertEquals(DEEP_REVIEW, source.recordCodexClean(run, version, edited.id(), transition, OPEN).state());
        assertEquals(ReviewConflict.Code.DUPLICATE_TRANSITION, assertThrows(ReviewConflict.class,
                () -> source.recordCodexClean(run, version, edited.id(), transition, OPEN)).code());
        signed.set(SignedAttempt.authentic(attempt(edited, 9001, Stage.BASELINE, run.headSha(), "attempt-other")));
        assertEquals(ReviewConflict.Code.TRANSITION_ID_REUSED, assertThrows(ReviewConflict.class,
                () -> source.recordCodexClean(run, version, edited.id(), transition, OPEN)).code());
        assertEquals(Map.of("trigger_comment_id", 9001L, "provider_execution_id", "fixture-execution",
                        "provider_attempt_id", "attempt-1", "stage", "BASELINE", "head_sha", repository.fixedSha),
                jdbc.queryForMap("""
                    SELECT trigger_comment_id, provider_execution_id, provider_attempt_id, stage, head_sha
                    FROM review_clean_evidence
                    """));
    }

    @Test
    void plannedRequestInheritsTheIntentPolicyAcrossANewerPermissiveVersion() throws Exception {
        planCorrectiveReview();
        var operator = new RepositoryPolicyOperator(new JdbcRepositoryPolicy(jdbc).operatorStore(transactions), authorization);
        operator.change(PR.scope(), 1, 1, Change.CONFIGURE,
                new RepositoryReviewPolicy(Set.of(Stage.BASELINE), Set.of("codex"), true, null, 4));
        assertTrue(plannedDispatch(new FixtureBudget(1)).processCommand());
        assertEquals(1L, jdbc.queryForObject("SELECT repository_policy_version FROM outbox_commands", Long.class));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT repository_policy_version FROM review_requests WHERE planned_command_id IS NOT NULL
                """, Long.class));
        assertThat(github.comments()).extracting(SandboxGitHub.Comment::id).containsExactly(9001L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void plannedDispatchCountsGenericReviewsAgainstItsPinnedCap(boolean reservedBeforeGeneric) throws Exception {
        var operator = new RepositoryPolicyOperator(new JdbcRepositoryPolicy(jdbc).operatorStore(transactions), authorization);
        var stages = Set.of(Stage.BASELINE, Stage.DEEP);
        var providers = Set.of("codex", "sandbox-deep");
        operator.change(PR.scope(), 1, 1, Change.CONFIGURE,
                new RepositoryReviewPolicy(stages, providers, false, null, 2));
        planCorrectiveReview();
        var dispatch = plannedDispatch(new FixtureBudget(1), budgetedRequests());
        if (reservedBeforeGeneric) {
            github.failNextPullReads(1);
            assertTrue(dispatch.processCommand());
            assertEquals("RESERVED", jdbc.queryForObject("""
                    SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                    """, String.class));
            assertSingleBudgetHold(repository.fixedSha);
        }

        operator.change(PR.scope(), 1, 2, Change.CONFIGURE,
                new RepositoryReviewPolicy(stages, providers, false, null, 3));
        var deep = new DurableWork.Intent(PR.number(), repository.fixedSha, "DEEP", "sandbox-deep", DurableWork.REQUEST_REVIEW);
        var genericEffect = new AtomicBoolean();
        var generic = new DurableDispatcher(queue, authorization, event -> List.of(deep), new CommandGateway() {
            @Override public boolean supports(DurableWork.Command command) { return deep.equals(command.intent()); }
            @Override public DurableWork.RemoteState reconcile(DurableWork.Command command) {
                return new DurableWork.RemoteState(github.head(), DurableWork.RemoteEffect.ABSENT);
            }
            @Override public void execute(DurableWork.Command command) { genericEffect.set(true); }
        });
        assertEquals(202, signed("pull_request", UUID.randomUUID(),
                pullWebhook("ready_for_review", repository.fixedSha)).statusCode());
        assertTrue(generic.processEvent());
        assertTrue(generic.processCommand());
        assertTrue(genericEffect.get());
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM outbox_commands WHERE command_key = ?",
                String.class, deep.key(PR.scope())));

        jdbc.update("UPDATE outbox_commands SET available_at = clock_timestamp() WHERE planned_head_epoch IS NOT NULL");
        assertTrue(dispatch.processCommand());
        assertThat(github.comments()).isEmpty();
        assertEquals(Map.of("status", "CANCELLED", "last_error_code", "POLICY_DENIED", "repository_policy_version", 2L),
                jdbc.queryForMap("""
                        SELECT status, last_error_code, repository_policy_version FROM outbox_commands
                        WHERE planned_head_epoch IS NOT NULL
                        """));
        if (reservedBeforeGeneric) {
            assertEquals("NOT_SENT", jdbc.queryForObject("""
                    SELECT state FROM review_requests WHERE planned_command_id IS NOT NULL
                    """, String.class));
            assertSingleBudgetHold(repository.fixedSha);
        } else {
            assertEquals(1, count("review_requests"));
            assertEquals(0, count("sandbox_codex_budget_reservations"));
            assertEquals(PullRequestReview.Intervention.ITERATION_LIMIT, current().intervention());
        }
    }

    @Test
    void twoRequestsForTheSameHeadRequireTheirOwnCommentAndAttempt() throws Exception {
        var initial = planCorrectiveReview();
        assertTrue(plannedDispatch(new FixtureBudget(1)).processCommand());
        var oldRequest = jdbc.queryForObject("SELECT id FROM review_requests WHERE planned_command_id IS NOT NULL", UUID.class);

        // A new same-head observation opens another durable request epoch for a separately authorized request.
        var sameHead = enqueue("pull_request", "synchronize", repository.fixedSha,
                prEnvelope("synchronize", repository.fixedSha, ",\"number\":7,\"before\":\"" + repository.fixedSha + "\""));
        assertFalse(fixes.observeSynchronize(sameHead, current(), head()));
        complete(sameHead);
        check(repository.fixedSha, Ci.PASSED);
        verifyFixtureInlineFix(initial);
        dispose(initial, GeneralFeedback.Action.DISMISS, null);
        long epoch = jdbc.queryForObject("""
                SELECT max(resulting_version) FROM pr_review_transitions WHERE action = 'CONFIRM_HEAD'
                """, Long.class);
        assertTrue(epoch > 0);
        var secondRequest = baseline(repository.fixedSha, ReviewRequest.Trigger.PR_SYNCHRONIZE, epoch);
        step(START_REVIEW, OPEN);
        assertEquals(Result.COMMENT_CREATED, codex(new FixtureBudget(1), 6).request(secondRequest));
        assertThat(github.comments()).extracting(SandboxGitHub.Comment::id).containsExactly(9001L, 9002L);
        var second = observeRound(secondRequest, "R3", Conclusion.NO_FINDINGS);

        var snapshot = ingestCompletion(second.run(), "");
        var observedComment = new java.util.concurrent.atomic.AtomicLong();
        var attempt = new AtomicReference<>(attempt(snapshot, 9001, Stage.BASELINE, repository.fixedSha, "attempt-1"));
        var source = new AssessReviewOutcome(new JdbcReviewOutcomeStore(jdbc, states), authorization,
                Optional.of((pr, commentId) -> {
                    assertEquals(PR, pr);
                    observedComment.set(commentId);
                    return Optional.of(attempt.get());
                }));
        long version = current().version();
        assertEquals(Verdict.UNKNOWN, source.assessCodex(second.run(), version, snapshot.id()).verdict());
        assertEquals(9002, observedComment.get());

        var current = second.run();
        var oldRun = new Run(PR, current.headSha(), current.provider(), current.stage(),
                oldRequest, current.cycleId(), current.evidenceProvider(), current.reviewer());
        assertEquals(Verdict.UNKNOWN, source.assessCodex(oldRun, version, snapshot.id()).verdict());
        attempt.set(attempt(snapshot, 9002, Stage.BASELINE, repository.fixedSha, "attempt-2"));
        assertEquals(Verdict.CLEAN_FOR_SHA, source.assessCodex(current, version, snapshot.id()).verdict());
        assertEquals(0, count("review_clean_evidence"));
    }

    private Assessment planCorrectiveReview() throws Exception {
        return planCorrectiveReview(requests);
    }

    private Assessment planCorrectiveReview(JdbcReviewRequestStore initialRequests) throws Exception {
        var initial = startRemediation(initialRequests);
        var worker = planningWorker((pr, intent) -> true, 6);
        github.push(repository.fixedSha);
        assertEquals(202, signed("pull_request", UUID.randomUUID(), pullWebhook("synchronize", github.head())).statusCode());
        assertTrue(worker.processEvent());
        dispose(initial, GeneralFeedback.Action.CLAIM_FIXED, github.head());
        successfulWorkflows();
        agePlanning();
        assertTrue(worker.processEvent());
        assertEquals(1, count("outbox_commands"));
        assertEquals(Ci.PASSED, current().ci());
        return initial;
    }

    private DispatchPlannedCodexReview plannedDispatch(CodexApproval approval) {
        return new DispatchPlannedCodexReview(queue, codex(approval, 6, CodexReviewAdapter.Mode.PLANNED), PR, 42);
    }

    private DispatchPlannedCodexReview plannedDispatch(CodexApproval approval, JdbcReviewRequestStore store) {
        return plannedDispatch(approval, store, () -> {});
    }

    private DispatchPlannedCodexReview plannedDispatch(CodexApproval approval, JdbcReviewRequestStore store,
            Runnable onCredentialLookup) {
        return new DispatchPlannedCodexReview(queue,
                codex(approval, 6, CodexReviewAdapter.Mode.PLANNED, store, onCredentialLookup), PR, 42);
    }

    private void dispatchAndPauseAfterReceipt() {
        var dispatch = plannedDispatch(new FixtureBudget(1), budgetedRequests(), () ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // The receipt is durable and the send transaction has released the PR lock.
                        step(PAUSE, OPEN);
                    }
                }));
        assertTrue(dispatch.processCommand());
    }

    private JdbcReviewRequestStore budgetedRequests() {
        return new JdbcReviewRequestStore(jdbc, transactions, states, new JdbcSandboxCodexBudget(jdbc, BUDGET_TERMS));
    }

    private void assertSingleBudgetHold(String head) {
        assertEquals(List.of(Map.of("head_sha", head, "usage_unit", BUDGET_TERMS.usageUnit(),
                "units", BUDGET_TERMS.maxPerRequest())), jdbc.queryForList("""
                SELECT r.head_sha, b.usage_unit, b.units FROM sandbox_codex_budget_reservations b
                JOIN review_requests r USING (request_key)
                """));
        long held = BUDGET_TERMS.maxPerRequest();
        assertThat(jdbc.queryForList("SELECT scope_key, reserved FROM sandbox_codex_budget_limits"))
                .containsExactlyInAnyOrder(Map.of("scope_key", "global", "reserved", held),
                        Map.of("scope_key", "installation:101", "reserved", held),
                        Map.of("scope_key", "pr:101:1001:7", "reserved", held));
    }

    private UUID assertPlannedReceipt() {
        assertThat(github.comments()).containsExactly(new SandboxGitHub.Comment(9001, repository.fixedSha, "@codex review"));
        assertEquals(2, count("review_requests"));
        return jdbc.queryForObject("""
                SELECT id FROM review_requests WHERE planned_command_id IS NOT NULL
                    AND state = 'COMMENT_CREATED' AND comment_id = 9001 AND iteration = 2
                """, UUID.class);
    }

    private int plannedStarts(UUID request) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pr_review_transitions WHERE transition_id = ? AND action = 'START_REVIEW'
                """, Integer.class, request);
    }

    private void replayPlannedCommand() {
        // Simulate recovery of the queue after the independent durable request transaction has committed.
        jdbc.update("""
                UPDATE outbox_commands SET status='PROCESSING', completed_at=NULL,
                    locked_until=clock_timestamp() - interval '1 second',
                    locked_by='restarted-fixture', lease_token=?
                """, UUID.randomUUID());
    }

    private ReviewEvidence.Snapshot ingestCompletion(Run run, String body) {
        var seen = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        String sourceId = "R2";
        var source = new ReviewEvidence.Source(sourceId, ReviewEvidence.Kind.REVIEW, sourceId, null, null,
                run.reviewer(), run.headSha(), null, Sha256.hex(body.getBytes(StandardCharsets.UTF_8)),
                seen, "COMMENTED", null, null, null, null, null, ReviewEvidence.Severity.UNKNOWN,
                ReviewEvidence.Feedback.NONE);
        var snapshot = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), PR, run.evidenceProvider(),
                run.headSha(), github.head(), seen, List.of(source));
        assertTrue(evidence.ingest(snapshot));
        return snapshot;
    }

    private TerminalAttempt attempt(ReviewEvidence.Snapshot snapshot, long commentId, Stage stage, String sha, String attemptId) {
        var source = snapshot.sources().getFirst();
        var provenance = new CodexProvenance(PR, commentId, sha, stage, "fixture-execution", attemptId,
                snapshot.capturedAt(), source.bodySha256(), source.updatedAt());
        return new TerminalAttempt(provenance, source.id(), source.author(), Status.COMPLETED, Conclusion.NO_FINDINGS);
    }

    private record SignedAttempt(TerminalAttempt attempt, byte[] signature) {
        static SignedAttempt authentic(TerminalAttempt attempt) {
            return new SignedAttempt(attempt, signAttempt(attempt));
        }
    }

    private static byte[] signAttempt(TerminalAttempt attempt) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("provider-attestation-fixture".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(attempt.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void successfulFinalSynchronizeAttemptStillPlansAfterQuietPeriod() throws Exception {
        var round = startRemediation();
        var worker = planningWorker((pr, intent) -> true, 6);
        String head = repository.fixedSha;
        var delivery = UUID.randomUUID();
        String push = pullWebhook("synchronize", head);
        github.push(head);
        github.failNextPullReads(4);
        assertEquals(202, signed("pull_request", delivery, push).statusCode());

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertTrue(worker.processEvent());
            assertEquals(Map.of("attempts", attempt, "status", "PENDING", "last_error_code", "REMOTE_UNAVAILABLE"),
                    jdbc.queryForMap("""
                            SELECT attempts, status, last_error_code FROM inbox_events WHERE delivery_id = ?
                            """, delivery.toString()));
            assertEquals(repository.openedSha, current().headSha());
            jdbc.update("UPDATE inbox_events SET available_at=clock_timestamp() WHERE delivery_id = ?", delivery.toString());
        }

        assertTrue(worker.processEvent(), "the fifth attempt records the fresh head");
        assertEquals(head, current().headSha());
        assertEquals(Ci.UNKNOWN, current().ci());
        assertEquals(200, signed("pull_request", delivery, push).statusCode());
        assertEquals(200, signed("pull_request", UUID.randomUUID(), push).statusCode());
        assertFalse(worker.processEvent(), "the assessment must wait for the quiet period");
        assertEquals(0, count("queue_dead_letters"), "a successful observation must not exhaust its follow-up");
        assertEquals(0, count("outbox_commands"));
        assertEquals(Map.of("attempts", 5, "status", "PROCESSED"), jdbc.queryForMap("""
                SELECT attempts, status FROM inbox_events WHERE delivery_id = ?
                """, delivery.toString()));
        assertEquals(Map.of("attempts", 0, "max_attempts", 5, "status", "PENDING"), jdbc.queryForMap("""
                SELECT attempts, max_attempts, status FROM inbox_events WHERE event_type = ?
                """, DurableWork.REVIEW_ASSESSMENT_EVENT));

        dispose(round, GeneralFeedback.Action.CLAIM_FIXED, head);
        successfulWorkflows();
        agePlanning();
        github.failNextPullReads(1);
        var restarted = planningWorker((pr, intent) -> true, 6);
        assertTrue(restarted.processEvent());
        assertEquals(Map.of("attempts", 1, "status", "PENDING", "last_error_code", "REMOTE_UNAVAILABLE"),
                jdbc.queryForMap("""
                        SELECT attempts, status, last_error_code FROM inbox_events WHERE event_type = ?
                        """, DurableWork.REVIEW_ASSESSMENT_EVENT));
        jdbc.update("UPDATE inbox_events SET available_at=clock_timestamp() WHERE event_type = ?",
                DurableWork.REVIEW_ASSESSMENT_EVENT);
        assertTrue(restarted.processEvent(), "the assessment resumes without a CI webhook");
        assertEquals(Ci.PASSED, current().ci());
        assertEquals(head, jdbc.queryForObject("SELECT expected_head_sha FROM outbox_commands", String.class));
        assertFalse(restarted.processEvent());
        assertEquals(1, count("outbox_commands"));
        assertEquals(0, count("queue_dead_letters"));
    }

    @Test
    void currentCiAndDispositionsMustPassBeforeBudgetCanPermitAnIntent() throws Exception {
        var round = startRemediation();
        var permit = new AtomicBoolean();
        var worker = planningWorker((pr, intent) -> permit.get(), 6);
        String head = repository.fixedSha;
        github.push(head);
        assertEquals(202, signed("pull_request", UUID.randomUUID(), pullWebhook("synchronize", head)).statusCode());
        assertTrue(worker.processEvent());
        agePlanning();
        successfulWorkflows();
        assertTrue(worker.processEvent());
        assertEquals(Ci.PASSED, current().ci());
        assertEquals(0, count("outbox_commands"), "general feedback has no disposition");

        dispose(round, GeneralFeedback.Action.CLAIM_FIXED, head);
        permit.set(true);
        github.workflowRuns(List.of(workflow(21, 1, 1, "completed", "failure"),
                workflow(22, 1, 1, "completed", "success")));
        assertEquals(202, signed("workflow_run", UUID.randomUUID(), ciWebhook("workflow_run", head, 31)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(Ci.FAILED, current().ci());
        assertEquals(0, count("outbox_commands"), "fresh failure supersedes the earlier passing CI");
        assertEquals(202, signed("workflow_run", UUID.randomUUID(),
                ciWebhook("workflow_run", repository.openedSha, 32)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(Ci.FAILED, current().ci());
        assertEquals(0, count("outbox_commands"));

        successfulWorkflows();
        step(PAUSE, OPEN);
        assertEquals(202, signed("workflow_run", UUID.randomUUID(), ciWebhook("workflow_run", head, 33)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(PAUSED, current().state());
        assertEquals(0, count("outbox_commands"));
        step(RESUME, OPEN);

        permit.set(false);
        assertEquals(202, signed("check_run", UUID.randomUUID(), ciWebhook("check_run", head, 34)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals(Ci.PASSED, current().ci());
        assertEquals(0, count("outbox_commands"), "the budget gate denies this exact intent");
        permit.set(true);
        assertEquals(202, signed("check_run", UUID.randomUUID(), ciWebhook("check_run", head, 35)).statusCode());
        assertTrue(planningWorker((pr, intent) -> permit.get(), 1).processEvent());
        assertEquals(PAUSED, current().state());
        assertEquals(PullRequestReview.Intervention.ITERATION_LIMIT, current().intervention());
        assertEquals(0, count("outbox_commands"));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void synchronizeInvalidatesCleanEvidenceAndReadinessFromTheEarlierHead() throws Exception {
        openReview();
        step(BASELINE_CLEAN, OPEN);
        step(DEEP_CLEAN, OPEN);
        check(repository.openedSha, Ci.PASSED);
        step(MARK_READY_FOR_HUMAN, OPEN);
        assertEquals(READY_FOR_HUMAN, current().state());

        github.push(repository.fixedSha);
        assertEquals(202, signed("pull_request", UUID.randomUUID(),
                pullWebhook("synchronize", repository.fixedSha)).statusCode());
        var worker = planningWorker((pr, intent) -> true, 6);
        assertTrue(worker.processEvent());
        var next = current();
        assertEquals(repository.fixedSha, next.headSha());
        assertEquals(WAITING_FOR_REVIEW, next.state());
        assertEquals(Ci.UNKNOWN, next.ci());
        assertNull(next.baselineCleanSha());
        assertNull(next.deepCleanSha());
        assertEquals(0, count("outbox_commands"), "a new head alone does not establish remediation");
        assertFalse(worker.processEvent());
    }

    @Test
    void rejectedOrRevokedScopeCannotAdvanceTheTrackedHead() throws Exception {
        startRemediation();
        var worker = planningWorker((pr, intent) -> true, 6);
        var before = current();
        github.push(repository.fixedSha);
        String push = pullWebhook("synchronize", repository.fixedSha);
        assertEquals(403, signed("pull_request", UUID.randomUUID(), push.replace(
                "\"id\":1001", "\"id\":2002")).statusCode());
        assertEquals(202, signed("pull_request", UUID.randomUUID(), push).statusCode());
        jdbc.update("UPDATE installation_repositories SET enabled=false");
        assertTrue(worker.processEvent());
        assertEquals("AUTHORIZATION_DENIED", jdbc.queryForObject("""
                SELECT last_error_code FROM inbox_events WHERE status='DEAD'
                """, String.class));
        jdbc.update("UPDATE installation_repositories SET enabled=true");
        assertEquals(before, current());
        assertEquals(0, count("outbox_commands"));
    }

    private Assessment startRemediation() throws Exception {
        return startRemediation(requests);
    }

    private Assessment startRemediation(JdbcReviewRequestStore initialRequests) throws Exception {
        var first = openReview();
        initialRequests.reserve(first, 42, 6, this::authorize, () -> {});
        var round = observeRound(first, "R1", Conclusion.FINDINGS);
        step(BLOCKING_FINDINGS, BLOCKED);
        step(AWAIT_FIX, OPEN);
        return round;
    }

    private DurableDispatcher planningWorker(ReviewIntentPolicy policy, int limit) {
        var reader = new GitHubSandboxCiReader(tokens, github.uri());
        var planner = new SandboxReviewPlanning(sandboxPolicy(), queue, authorization, authorization, lifecycle,
                reader, new JdbcSandboxReviewPlanning(jdbc, queue, states, fixStore, policy, limit));
        return new DurableDispatcher(queue, authorization, planner::complete);
    }

    private void successfulWorkflows() {
        github.workflowRuns(List.of(workflow(21, 2, 1, "completed", "success"),
                workflow(22, 2, 1, "completed", "success")));
    }

    private void agePlanning() {
        ageSynchronize();
        jdbc.update("UPDATE inbox_events SET available_at=clock_timestamp() WHERE status='PENDING'");
    }

    private void drainPlanning(DurableDispatcher worker) {
        for (int i = 0; i < 5; i++) {
            jdbc.update("UPDATE inbox_events SET available_at=clock_timestamp() WHERE status='PENDING'");
            if (!worker.processEvent()) break;
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE status <> 'PROCESSED'", Integer.class));
    }

    private DurableDispatcher ciWorker() {
        var reader = new GitHubSandboxCiReader(tokens, github.uri());
        var reconciler = new SandboxCiReconciler(sandboxPolicy(), queue, authorization, lifecycle, reader,
                new JdbcSandboxCiInbox(jdbc, queue, states));
        return new DurableDispatcher(queue, authorization, reconciler);
    }

    @Test
    void durableWorkerReconcilesSignedReviewAndInlineEventsWithoutDuplicateFindings() throws Exception {
        reviewEvidence(false, "original inline", repository.openedSha);
        var worker = reviewWorker();
        var deliveryId = UUID.randomUUID();
        var review = reviewWebhook("submitted", "review", repository.openedSha, 7);
        assertEquals(401, send("pull_request_review", deliveryId, review, "sha256=" + "0".repeat(64)).statusCode());
        assertEquals(202, signed("pull_request_review", UUID.randomUUID(),
                reviewWebhook("submitted", "review", repository.fixedSha, 7)).statusCode());
        assertEquals(202, signed("pull_request_review", UUID.randomUUID(),
                reviewWebhook("submitted", "review", repository.openedSha, 8)).statusCode());
        assertTrue(worker.processEvent());
        assertTrue(worker.processEvent());
        assertEquals(0, github.reviewReads());

        var accepted = signed("pull_request_review", deliveryId, review);
        assertEquals(202, accepted.statusCode());
        assertTrue(worker.processEvent());
        var repeated = signed("pull_request_review", deliveryId, review);
        assertEquals(200, repeated.statusCode());
        assertEquals(accepted.headers().firstValue("X-Correlation-ID"), repeated.headers().firstValue("X-Correlation-ID"));
        assertFalse(worker.processEvent());
        var finding = jdbc.queryForObject("SELECT id FROM finding_identities WHERE source_kind='REVIEW_COMMENT'", UUID.class);

        reviewEvidence(true, "original inline", repository.openedSha);
        assertEquals(202, signed("pull_request_review_comment", UUID.randomUUID(),
                reviewWebhook("created", "comment", repository.openedSha, 7)).statusCode());
        assertTrue(worker.processEvent());
        assertFalse(worker.processCommand());
        assertEquals(2, github.reviewReads());
        assertEquals(1, count("review_cycles"));
        assertEquals(2, count("review_evidence_snapshots"));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE status='PROCESSED'", Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM inbox_events i JOIN review_evidence_snapshots s ON s.id = i.id
                """, Integer.class));
        var inline = jdbc.queryForMap("""
                SELECT f.stable_finding_id, f.status, f.verified_at, s.reviewed_sha, s.original_sha, s.thread_resolved
                FROM findings f JOIN review_evidence_sources s
                    ON f.evidence_snapshot_id = s.snapshot_id AND f.source_id = s.source_id
                WHERE f.source_kind='REVIEW_COMMENT'
                """);
        assertEquals(finding, inline.get("stable_finding_id"));
        assertEquals("OPEN", inline.get("status"));
        assertNull(inline.get("verified_at"));
        assertEquals(repository.openedSha, inline.get("reviewed_sha"));
        assertEquals(repository.openedSha, inline.get("original_sha"));
        assertEquals(true, inline.get("thread_resolved"));
        assertEquals(0, count("pr_review_transitions"));
        assertEquals(0, count("outbox_commands"));
        assertEquals(0, count("review_requests"));
        assertEquals(0, count("author_notifications"));
        assertThat(github.comments()).isEmpty();
    }

    @Test
    void durableReviewWorkerRetriesIncompleteReadsAndRollsBackEvidenceOnDeferredFailure() throws Exception {
        reviewEvidence(false, "original inline", repository.openedSha, "different REST revision");
        var worker = reviewWorker();
        assertEquals(202, signed("pull_request_review", UUID.randomUUID(),
                reviewWebhook("submitted", "review", repository.openedSha, 7)).statusCode());
        assertTrue(worker.processEvent());
        assertEquals("REMOTE_UNAVAILABLE", jdbc.queryForObject("SELECT last_error_code FROM inbox_events", String.class));
        assertEquals(0, count("review_cycles"));
        reviewEvidence(false, "original inline", repository.openedSha);
        jdbc.execute("UPDATE inbox_events SET available_at=clock_timestamp()");
        jdbc.execute("""
                CREATE FUNCTION reject_review_snapshot_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'fixture deferred review failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_review_snapshot_commit AFTER INSERT ON review_evidence_snapshots
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_review_snapshot_commit()
                """);
        try {
            assertTrue(worker.processEvent());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM inbox_events", String.class));
            assertEquals(0, count("review_cycles"));
            assertEquals(0, count("review_evidence_snapshots"));
            assertEquals(0, count("findings"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_review_snapshot_commit ON review_evidence_snapshots");
            jdbc.execute("DROP FUNCTION reject_review_snapshot_commit()");
        }

        reviewEvidence(false, "new inline", repository.fixedSha);
        jdbc.execute("UPDATE inbox_events SET available_at=clock_timestamp()");
        assertTrue(reviewWorker().processEvent());
        assertEquals(3, github.reviewReads());
        assertEquals(3, jdbc.queryForObject("SELECT attempts FROM inbox_events WHERE status='PROCESSED'", Integer.class));
        assertEquals(2, count("review_evidence_snapshots"));
        assertEquals(repository.fixedSha, jdbc.queryForObject("""
                SELECT original_sha FROM review_evidence_sources WHERE source_kind='REVIEW_COMMENT'
                """, String.class));
        assertEquals(Sha256.hex("new inline".getBytes(StandardCharsets.UTF_8)), jdbc.queryForObject("""
                SELECT body_sha256 FROM review_evidence_sources WHERE source_kind='REVIEW_COMMENT'
                """, String.class));
    }

    private SandboxCiReconciler.Policy sandboxPolicy() {
        return new SandboxCiReconciler.Policy(PR, PR.scope(), SandboxGitHub.REPOSITORY,
                repository.openedSha, Set.of(21L, 22L));
    }

    private DurableDispatcher reviewWorker() {
        return reviewWorker(repository.openedSha);
    }

    private DurableDispatcher reviewWorker(String head) {
        var reader = new GitHubSandboxReviewReader(tokens, github.uri(), clock, normalizer);
        var policy = new SandboxCiReconciler.Policy(PR, PR.scope(), SandboxGitHub.REPOSITORY, head, Set.of(21L, 22L));
        var reviews = new SandboxReviewReconciler(policy, queue, authorization, reader,
                new JdbcSandboxReviewInbox(jdbc, queue, states, evidenceStore));
        return new DurableDispatcher(queue, authorization, reviews::complete);
    }

    private String reviewWebhook(String action, String subject, String head, int number) {
        return """
                {"action":"%s","installation":{"id":101},"repository":{"id":1001},
                 "pull_request":{"number":%d,"head":{"sha":"%s"}},"%s":{"id":1}}
                """.formatted(action, number, head, subject);
    }

    private void reviewEvidence(boolean resolved, String body, String original) {
        reviewEvidence(resolved, body, original, body);
    }

    private void reviewEvidence(boolean resolved, String body, String original, String restBody) {
        github.reviewEvidence("""
                {"data":{"repository":{"databaseId":1001,"pullRequest":{
                    "number":7,"headRefOid":"%s","state":"OPEN","isDraft":false,
                    "comments":{"totalCount":0,"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "reviews":{"totalCount":1,"pageInfo":{"hasNextPage":false},"nodes":[{"id":"review-1",
                        "state":"COMMENTED","body":"","updatedAt":"2026-11-19T12:00:00Z",
                        "author":{"__typename":"User","login":"fixture-reviewer"}}]},
                    "reviewThreads":{"totalCount":1,"pageInfo":{"hasNextPage":false},"nodes":[{"id":"thread-1",
                        "isResolved":%s,"isOutdated":false,"path":"Example.java","line":8,"originalLine":8,
                        "comments":{"totalCount":1,"pageInfo":{"hasNextPage":false},"nodes":[{"id":"inline-1",
                            "body":"%s","updatedAt":"2026-11-19T12:00:00Z",
                            "author":{"__typename":"User","login":"fixture-reviewer"},
                            "path":"Example.java","line":8,"originalLine":8,
                            "pullRequestReview":{"id":"review-1"},"replyTo":null}]}}]}
                    }}}}
                """.formatted(github.head(), resolved, body), """
                [{"id":5932554900,"node_id":"review-1","state":"COMMENTED","body":"","commit_id":"%s"}]
                """.formatted(original), """
                [{"id":4595103520,"node_id":"inline-1","body":"%s","updated_at":"2026-11-19T12:00:00Z","path":"Example.java",
                  "pull_request_review_id":5932554900,"commit_id":"%s","original_commit_id":"%s"}]
                """.formatted(restBody, github.head(), original));
    }

    private Map<String, Object> workflow(long workflow, long sequence, long attempt, String status, String conclusion) {
        var repo = Map.of("id", 1001);
        return Map.of("workflow_id", workflow, "id", workflow * 100 + sequence, "run_number", sequence,
                "run_attempt", attempt, "head_sha", github.head(), "event", "pull_request", "repository", repo,
                "status", status, "conclusion", conclusion,
                "pull_requests", List.of(Map.of("number", 7, "base", Map.of("repo", repo))));
    }

    private String ciWebhook(String type, String head, int id) {
        return """
                {"action":"completed","installation":{"id":101},"repository":{"id":1001},
                 "%s":{"id":%d,"head_sha":"%s","conclusion":"success"}}
                """.formatted(type, id, head);
    }

    @Test
    void replaySafeFeedbackLoopNotifiesOnceWithPassingCiAndCurrentReviewEvidence() throws Exception {
        var provider = codex(new FixtureBudget(2), 6);
        var opening = pullWebhook("opened", repository.openedSha);
        var id = UUID.randomUUID();
        assertEquals(401, send("pull_request", id, opening, "sha256=" + "0".repeat(64)).statusCode());
        var foreign = opening.replace("\"id\":1001", "\"id\":2002");
        assertEquals(403, signed("pull_request", id, foreign).statusCode());
        assertTrue(queue.claimEvent().isEmpty());
        assertThat(github.comments()).isEmpty();

        var accepted = signed("pull_request", id, opening);
        var replay = signed("pull_request", id, opening);
        assertEquals(202, accepted.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(accepted.headers().firstValue("X-Correlation-ID"), replay.headers().firstValue("X-Correlation-ID"));
        assertEquals(200, signed("pull_request", UUID.randomUUID(), opening).statusCode());
        var first = startReview(claim("pull_request", "opened", repository.openedSha));
        assertEquals(Result.COMMENT_CREATED, provider.request(first));
        assertEquals(Result.DUPLICATE, codex(new FixtureBudget(0), 6).request(first));
        assertTrue(queue.claimEvent().isEmpty());

        var firstRound = observeRound(first, "R1", Conclusion.FINDINGS);
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(firstRound).verdict());
        assertThat(jdbc.queryForList("SELECT source_kind FROM findings", String.class))
                .containsExactlyInAnyOrder("REVIEW_COMMENT", "REVIEW");
        step(BLOCKING_FINDINGS, BLOCKED);
        assertEquals(ACTION_REQUIRED, current().state());
        step(AWAIT_FIX, OPEN);
        assertTrue(notices.processNext(), "superseded action notice must be skipped before sending");

        var synchronize = push(repository.fixedSha);
        assertTrue(fixes.observeSynchronize(synchronize, current(), head()));
        var afterPush = current();
        assertTrue(fixes.observeSynchronize(synchronize, afterPush, head()));
        assertEquals(afterPush, current());
        complete(synchronize);
        assertEquals(Ci.UNKNOWN, current().ci());
        assertEquals(ReviewConflict.Code.STALE_VERSION,
                assertThrows(ReviewConflict.class, () -> outcomes.assess(firstRound)).code());
        var stale = new Assessment(firstRound.run(), afterPush.version(), firstRound.completion(), firstRound.snapshotId());
        assertEquals(ReviewConflict.Code.STALE_HEAD, assertThrows(ReviewConflict.class, () -> outcomes.assess(stale)).code());

        assertEquals(FixCorrelation.Status.WAITING_FOR_PUSHES, assessFix(repository.openedSha).status());
        ageSynchronize();
        assertEquals(FixCorrelation.Status.GENERAL_DISPOSITION_REQUIRED, assessFix(repository.openedSha).status());
        dispose(firstRound, GeneralFeedback.Action.CLAIM_FIXED, repository.fixedSha);
        var candidate = assessFix(repository.openedSha);
        assertEquals(FixCorrelation.Status.ELIGIBLE, candidate.status());
        assertEquals(candidate.candidateId(), assessFix(repository.openedSha).candidateId());
        var rerun = baseline(repository.fixedSha, ReviewRequest.Trigger.PR_SYNCHRONIZE, candidate.headEpoch());

        check(repository.openedSha, Ci.PASSED);
        check(repository.fixedSha, Ci.FAILED);
        assertCode(ReviewRequestFailure.Code.CI_NOT_PASSED, () -> provider.request(rerun));
        assertThat(github.comments()).hasSize(1);
        check(repository.fixedSha, Ci.PASSED);
        step(START_REVIEW, OPEN);
        assertEquals(Result.COMMENT_CREATED, provider.request(rerun));
        assertEquals(Result.DUPLICATE, provider.request(rerun));

        var clean = observeRound(rerun, "R2", Conclusion.NO_FINDINGS);
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(clean).verdict(),
                "a fix claim and a clean response cannot clear retained findings");
        verifyFixtureInlineFix(firstRound);
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(clean).verdict(), "general fix claim still blocks");
        dispose(firstRound, GeneralFeedback.Action.DISMISS, null);
        var wrongRequest = new Run(PR, repository.fixedSha, rerun.provider(), Stage.BASELINE, firstRound.run().requestId(),
                clean.run().cycleId(), clean.run().evidenceProvider(), clean.run().reviewer());
        var miscorrelated = new Assessment(wrongRequest, current().version(),
                new Completion(wrongRequest, "R2", Status.COMPLETED, Conclusion.NO_FINDINGS), clean.snapshotId());
        assertEquals(Verdict.UNKNOWN, outcomes.assess(miscorrelated).verdict());
        var readyBaseline = outcomes.recordClean(clean, UUID.randomUUID(), OPEN);
        assertEquals(DEEP_REVIEW, readyBaseline.state());

        var deep = new ReviewRequest(PR, repository.fixedSha, "DEEP", "sandbox-deep", ReviewRequest.Trigger.PR_SYNCHRONIZE, 42, false);
        var deepBudget = new FixtureBudget(1);
        var ticket = requests.reserve(deep, 42, 6, this::authorize, () -> deepBudget.requirePermitted(deep));
        requests.send(deep, ticket.id(), this::authorize, finalGuards -> new Receipt(9901, true));
        var deepClean = observeRound(deep, "R3", Conclusion.NO_FINDINGS);
        outcomes.recordClean(deepClean, UUID.randomUUID(), OPEN);
        check(repository.fixedSha, Ci.PENDING);
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> step(MARK_READY_FOR_HUMAN, OPEN)).code());
        assertFalse(notices.processNext());
        check(repository.fixedSha, Ci.PASSED);
        var ready = transition(MARK_READY_FOR_HUMAN, OPEN);
        assertEquals(READY_FOR_HUMAN, lifecycle.apply(PR, ready).state());
        assertEquals(ReviewConflict.Code.DUPLICATE_TRANSITION,
                assertThrows(ReviewConflict.class, () -> lifecycle.apply(PR, ready)).code());
        assertFalse(notices.processNext());
        step(PAUSE, OPEN);
        step(RESUME, OPEN);
        assertFalse(notices.processNext());
        assertFalse(notices.processNext());

        assertThat(github.comments()).extracting(SandboxGitHub.Comment::head)
                .containsExactly(repository.openedSha, repository.fixedSha, repository.fixedSha);
        assertThat(github.comments().subList(0, 2)).extracting(SandboxGitHub.Comment::body).containsOnly("@codex review");
        var notice = github.comments().getLast();
        assertThat(notice.body()).contains("@fixture-author **Ready for human review**", repository.fixedSha, "CI at dispatch: PASSED",
                "Codex", "Approval and any merge decision require human action")
                .doesNotContain(repository.openedSha, SandboxGitHub.PRIVATE_TEXT, SECRET, SandboxGitHub.APP_TOKEN, SandboxGitHub.USER_TOKEN);
        assertThat(jdbc.queryForList("SELECT comment_id FROM author_notifications WHERE status = 'SENT'", Long.class))
                .containsExactly(notice.id());
        var comment = enqueue("issue_comment", "created", null, """
                {"action":"created","installation":{"id":101},"repository":{"id":1001},
                 "issue":{"number":7,"pull_request":{}},"comment":{"id":%d},"sender":{"id":98,"type":"Bot"}}
                """.formatted(notice.id()));
        var noticeTrigger = new ReviewRequest(PR, github.head(), "BASELINE", "codex", ReviewRequest.Trigger.COMMENT, 98, true);
        assertEquals(Result.IGNORED, provider.request(noticeTrigger));
        complete(comment);
        assertThat(github.comments()).hasSize(3);
        assertEquals(3, count("review_requests"));
        assertReviewShaEvidence();
    }

    @Test
    void repeatedComparableFindingsPauseTheLoopAfterTwoPostedRequests() throws Exception {
        var provider = codex(new FixtureBudget(6), 6);
        var first = openReview();
        assertEquals(Result.COMMENT_CREATED, provider.request(first));
        var initial = observeRound(first, "R1", Conclusion.FINDINGS);
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(initial).verdict());
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(initial).verdict());
        var second = moveToFix(initial);
        assertEquals(Result.COMMENT_CREATED, provider.request(second));
        var repeated = observeRound(second, "R2", Conclusion.FINDINGS);
        assertEquals(Reason.NO_PROGRESS, outcomes.assess(repeated).reason());
        var paused = current();
        assertEquals(PAUSED, paused.state());
        assertEquals(PullRequestReview.Intervention.NO_PROGRESS, paused.intervention());
        assertEquals(Reason.NO_PROGRESS, outcomes.assess(
                new Assessment(repeated.run(), paused.version(), repeated.completion(), repeated.snapshotId())).reason());
        assertEquals(paused, current());

        advance(repository.followUpSha);
        assertCode(ReviewRequestFailure.Code.HUMAN_INTERVENTION_REQUIRED,
                () -> provider.request(baseline(repository.followUpSha, ReviewRequest.Trigger.PR_SYNCHRONIZE, 0)));
        assertThat(github.comments()).hasSize(2);
        assertEquals(2, count("review_requests"));
        assertEquals(0, count("review_clean_evidence"));
    }

    @Test
    void deniedBudgetReservesNothingAndIterationLimitStopsBeforeAnotherPost() throws Exception {
        var first = openReview();
        assertCode(ReviewRequestFailure.Code.POLICY_DENIED, () -> codex(new FixtureBudget(0), 2).request(first));
        assertEquals(0, count("review_requests"));
        assertThat(github.comments()).isEmpty();

        var provider = codex(new FixtureBudget(6), 2);
        assertEquals(Result.COMMENT_CREATED, provider.request(first));
        var initial = observeRound(first, "R1", Conclusion.FINDINGS);
        assertEquals(Verdict.ACTION_REQUIRED, outcomes.assess(initial).verdict());
        var second = moveToFix(initial);
        assertEquals(Result.COMMENT_CREATED, provider.request(second));
        assertEquals(Result.DUPLICATE, provider.request(second));
        advance(repository.followUpSha);
        assertCode(ReviewRequestFailure.Code.LIMIT_REACHED,
                () -> provider.request(baseline(repository.followUpSha, ReviewRequest.Trigger.PR_SYNCHRONIZE, 0)));
        assertEquals(PAUSED, current().state());
        assertEquals(PullRequestReview.Intervention.ITERATION_LIMIT, current().intervention());
        assertEquals(2, count("review_requests"));
        assertThat(github.comments()).hasSize(2);
        assertEquals(0, count("review_clean_evidence"));
    }

    @Test
    void ambiguousCommentReceiptSurvivesWorkerRecreationWithoutResending() throws Exception {
        var request = openReview();
        github.rejectCommentReceipt();
        assertEquals(Result.OUTCOME_UNKNOWN, codex(new FixtureBudget(1), 6).request(request));
        assertEquals(Result.OUTCOME_UNKNOWN, codex(new FixtureBudget(0), 6).request(request));
        assertThat(github.comments()).extracting(SandboxGitHub.Comment::body).containsExactly("@codex review");
        assertThat(jdbc.queryForMap("SELECT state, comment_id, head_sha FROM review_requests"))
                .containsEntry("state", "ATTEMPTED").containsEntry("comment_id", null).containsEntry("head_sha", repository.openedSha);
        assertEquals(0, count("review_clean_evidence"));
        assertFalse(notices.processNext());
    }

    private void assertReviewShaEvidence() {
        assertThat(jdbc.queryForList("""
                SELECT p.review_source_id || '/' || r.head_sha || '/' || c.head_sha || '/' || s.reviewed_sha
                       || '/' || o.head_sha || '/' || o.observed_head_sha
                FROM review_round_progress p JOIN review_requests r ON r.id = p.request_id
                JOIN review_evidence_snapshots o ON o.id = p.snapshot_id JOIN review_cycles c ON c.id = o.review_cycle_id
                JOIN review_evidence_sources s ON s.snapshot_id = o.id AND s.source_id = p.review_source_id
                ORDER BY p.review_source_id
                """, String.class)).containsExactly("R1" + ("/" + repository.openedSha).repeat(5),
                "R2" + ("/" + repository.fixedSha).repeat(5), "R3" + ("/" + repository.fixedSha).repeat(5));
        assertThat(jdbc.queryForList("""
                SELECT r.head_sha || '/' || c.head_sha || '/' || e.head_sha || '/' || e.review_source_id
                FROM review_clean_evidence e JOIN review_requests r ON r.id = e.request_id
                JOIN review_cycles c ON c.id = e.cycle_id ORDER BY e.review_source_id
                """, String.class)).containsExactly(repository.fixedSha + "/" + repository.fixedSha + "/" + repository.fixedSha + "/R2",
                repository.fixedSha + "/" + repository.fixedSha + "/" + repository.fixedSha + "/R3");
    }

    private Assessment observeRound(ReviewRequest request, String reviewId, Conclusion conclusion) throws Exception {
        var receipt = jdbc.queryForObject("SELECT id, iteration FROM review_requests WHERE request_key = ?",
                (rs, row) -> new RoundReceipt(rs.getObject("id", UUID.class), rs.getInt("iteration")), request.key());
        var cycle = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                VALUES (?, 101, 1001, 7, ?, ?, ?, ?)
                """, cycle, request.headSha(), request.stage(), request.provider(), receipt.iteration());
        String sha = request.headSha();
        var event = enqueue("pull_request_review", "submitted", sha,
                prEnvelope("submitted", sha, ",\"review\":{\"id\":" + (100 + receipt.iteration()) + "}"));
        boolean findings = conclusion == Conclusion.FINDINGS;
        var updatedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var snapshot = normalizer.normalize(UUID.randomUUID(), cycle, PR, github.head(), updatedAt,
                reviewJson(reviewId, sha, updatedAt, findings).getBytes(StandardCharsets.UTF_8),
                Map.of(reviewId + "-inline", ReviewEvidence.Severity.HIGH), findings ? Set.of(reviewId) : Set.of());
        assertTrue(evidence.ingest(snapshot));
        assertFalse(evidence.ingest(snapshot));
        complete(event);
        var run = new Run(PR, sha, request.provider(), Stage.valueOf(request.stage()), receipt.id(), cycle,
                "github", "fixture-reviewer");
        return new Assessment(run, current().version(), new Completion(run, reviewId, Status.COMPLETED, conclusion), snapshot.id());
    }

    private record RoundReceipt(UUID id, int iteration) {}

    private String reviewJson(String reviewId, String sha, Instant seen, boolean findings) {
        String thread = findings ? """
                {"id":"%1$s-thread","isResolved":false,"isOutdated":false,"path":"Example.java","line":1,"originalLine":1,
                 "comments":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":"%1$s-inline","body":"Check the edge case",
                 "author":{"login":"fixture-reviewer"},"commit":{"oid":"%2$s"},"originalCommit":{"oid":"%2$s"},
                 "pullRequestReview":{"id":"%1$s"},"replyTo":null,"updatedAt":"%3$s",
                 "path":"Example.java","line":1,"originalLine":1}]}}
                """.formatted(reviewId, sha, seen) : "";
        return """
                {"data":{"repository":{"databaseId":1001,"pullRequest":{"number":7,"headRefOid":"%1$s",
                 "comments":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                 "reviews":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":"%2$s","state":"COMMENTED",
                 "author":{"login":"fixture-reviewer"},"commit":{"oid":"%1$s"},"updatedAt":"%3$s","body":"%4$s"}]},
                 "reviewThreads":{"pageInfo":{"hasNextPage":false},"nodes":[%5$s]}}}}}
                """.formatted(sha, reviewId, seen, findings ? SandboxGitHub.PRIVATE_TEXT : "", thread);
    }

    private void verifyFixtureInlineFix(Assessment initial) throws IOException, InterruptedException {
        assertTrue(repository.verifiesFix(initial.run().headSha(), github.head()));
        var finding = jdbc.queryForObject("""
                SELECT stable_finding_id FROM findings
                WHERE evidence_snapshot_id = ? AND source_kind = 'REVIEW_COMMENT'
                """, UUID.class, initial.snapshotId());
        var state = current();
        var verifier = new VerifySandboxInlineFix(PR, repository.fixedSha, authorization, inlineFixes);
        String originalBlob = repository.blob(repository.openedSha);
        String correctiveBlob = repository.blob(repository.fixedSha);
        String actor = "fixture-verifier";
        String reason = "The committed source now returns the expected value.";
        assertThrows(IllegalArgumentException.class, () -> new SandboxInlineFix(finding, initial.snapshotId(),
                state.version(), repository.openedSha, repository.fixedSha, "Example.java",
                originalBlob, originalBlob, actor, reason));
        assertThrows(ReviewEvidence.Conflict.class, () -> verifier.verify(new SandboxInlineFix(finding,
                initial.snapshotId(), state.version() - 1, repository.openedSha, repository.fixedSha, "Example.java",
                originalBlob, correctiveBlob, actor, reason)));
        assertThrows(ReviewEvidence.Conflict.class, () -> verifier.verify(new SandboxInlineFix(finding,
                initial.snapshotId(), state.version(), repository.openedSha, repository.fixedSha, "Unrelated.java",
                originalBlob, correctiveBlob, actor, reason)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM findings WHERE status='VERIFIED_FIXED'", Integer.class));
        var proof = new SandboxInlineFix(finding, initial.snapshotId(), state.version(), repository.openedSha,
                repository.fixedSha, "Example.java", originalBlob, correctiveBlob, actor, reason);
        assertTrue(verifier.verify(proof));
        var recorded = jdbc.queryForMap("""
                SELECT status, evidence_sha, verified_at FROM findings WHERE stable_finding_id = ?
                """, finding);
        assertEquals("VERIFIED_FIXED", recorded.get("status"));
        assertEquals(repository.fixedSha, recorded.get("evidence_sha"));
        assertNotNull(recorded.get("verified_at"));
        assertFalse(verifier.verify(proof));
        assertEquals(recorded, jdbc.queryForMap("""
                SELECT status, evidence_sha, verified_at FROM findings WHERE stable_finding_id = ?
                """, finding));
        assertEquals(state, current());
    }

    private void dispose(Assessment round, GeneralFeedback.Action action, String evidenceSha) {
        var finding = jdbc.queryForObject("SELECT id FROM finding_identities WHERE source_kind = 'REVIEW' AND source_id = ?",
                UUID.class, round.completion().reviewId());
        var state = current();
        assertTrue(feedback.dispose(PR, new GeneralFeedback.Disposition(UUID.randomUUID(), finding, round.snapshotId(),
                state.headSha(), state.version(), action, "fixture-author", "Sandbox author disposition", evidenceSha)));
    }

    private ReviewRequest openReview() throws Exception {
        return startReview(enqueue("pull_request", "opened", repository.openedSha, pullWebhook("opened", repository.openedSha)));
    }

    private ReviewRequest startReview(Event opened) {
        assertEquals(github.head(), opened.observedHeadSha());
        lifecycle.create(PR, opened.observedHeadSha());
        step(MARK_READY, OPEN);
        step(START_REVIEW, OPEN);
        complete(opened);
        return baseline(opened.observedHeadSha(), ReviewRequest.Trigger.PR_OPENED, 0);
    }

    private ReviewRequest moveToFix(Assessment initial) throws Exception {
        step(BLOCKING_FINDINGS, BLOCKED);
        step(AWAIT_FIX, OPEN);
        advance(repository.fixedSha);
        ageSynchronize();
        dispose(initial, GeneralFeedback.Action.CLAIM_FIXED, repository.fixedSha);
        var decision = assessFix(repository.openedSha);
        assertEquals(FixCorrelation.Status.ELIGIBLE, decision.status());
        step(START_REVIEW, OPEN);
        return baseline(repository.fixedSha, ReviewRequest.Trigger.PR_SYNCHRONIZE, decision.headEpoch());
    }

    private void advance(String sha) throws Exception {
        var event = push(sha);
        assertTrue(fixes.observeSynchronize(event, current(), head()));
        complete(event);
        check(sha, Ci.PASSED);
    }

    private Event push(String sha) throws Exception {
        github.push(sha);
        return enqueue("pull_request", "synchronize", sha, pullWebhook("synchronize", sha));
    }

    private void check(String eventSha, Ci status) throws Exception {
        var event = enqueue("check_run", "completed", eventSha, """
                {"action":"completed","installation":{"id":101},"repository":{"id":1001},
                 "check_run":{"id":%d,"head_sha":"%s","conclusion":"success"}}
                """.formatted(nextCheckRunId++, eventSha));
        var required = new CiEvidence.Check(2, "build");
        var read = new CiEvidence(event.lease().id(), PR, github.head(), Set.of(required),
                List.of(new CiEvidence.Run(required, github.head(), status)), true);
        var result = lifecycle.observeCi(event, current().version(), read);
        if (eventSha.equals(github.head())) {
            assertEquals(status, result.orElseThrow().ci());
        } else {
            assertTrue(result.isEmpty());
            assertEquals(Ci.UNKNOWN, current().ci());
        }
        complete(event);
    }

    private FixCorrelation.Head head() {
        return new FixCorrelation.Head(PR, github.head(), true, false);
    }

    private FixCorrelation.Decision assessFix(String baseSha) throws IOException, InterruptedException {
        return fixes.assess(new FixCorrelation.Assessment(head(), current().version(), false,
                new FixCorrelation.Comparison(baseSha, github.head(), repository.changed(baseSha, github.head()))));
    }

    private void ageSynchronize() {
        // Move the fixture past the persisted quiet period without sleeping or changing the production timer.
        assertEquals(1, jdbc.update("""
                UPDATE pr_review_transitions SET occurred_at = clock_timestamp() - interval '31 seconds'
                WHERE action = 'UPDATE_HEAD' AND resulting_version = (
                    SELECT max(resulting_version) FROM pr_review_transitions WHERE action = 'UPDATE_HEAD')
                """));
    }

    private ReviewRequest baseline(String sha, ReviewRequest.Trigger trigger, long epoch) {
        return new ReviewRequest(PR, sha, "BASELINE", "codex", trigger, 52, false, epoch);
    }

    private CodexReviewAdapter codex(CodexApproval approval, int limit) {
        return codex(approval, limit, CodexReviewAdapter.Mode.STANDARD);
    }

    private CodexReviewAdapter codex(CodexApproval approval, int limit, CodexReviewAdapter.Mode mode) {
        return codex(approval, limit, mode, requests, () -> {});
    }

    private CodexReviewAdapter codex(CodexApproval approval, int limit, CodexReviewAdapter.Mode mode,
            JdbcReviewRequestStore store, Runnable onCredentialLookup) {
        var credential = new Credential(PR.scope(), 42, SandboxGitHub.REPOSITORY, SandboxGitHub.USER_TOKEN,
                clock.instant().plus(TOKEN_LIFETIME));
        var client = new GitHubCodexComments(scope -> {
            onCredentialLookup.run();
            return credential;
        }, clock, github.uri());
        return new CodexReviewAdapter(authorization, store, approval, client, 42, limit, mode);
    }

    private static final class FixtureBudget implements CodexApproval {
        private final int allowance;
        private final Set<String> reserved = new HashSet<>();

        FixtureBudget(int allowance) {
            this.allowance = allowance;
        }
        @Override public void requirePermitted(ReviewRequest request) {
            if (!reserved.contains(request.key()) && reserved.size() >= allowance) {
                throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            }
            reserved.add(request.key());
        }

    }

    private PullRequestReview current() {
        return lifecycle.find(PR).orElseThrow();
    }

    private ReviewTransition transition(ReviewTransition.Action action, ReviewTransition.Gates gates) {
        var state = current();
        return new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action, null, gates);
    }

    private void step(ReviewTransition.Action action, ReviewTransition.Gates gates) {
        lifecycle.apply(PR, transition(action, gates));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void authorize() {
        authorization.requireAuthorized(PR.scope());
    }

    private void assertCode(ReviewRequestFailure.Code code, Runnable operation) {
        assertEquals(code, assertThrows(ReviewRequestFailure.class, operation::run).code());
    }

    private Event enqueue(String type, String action, String sha, String body) throws Exception {
        assertEquals(202, signed(type, UUID.randomUUID(), body).statusCode());
        return claim(type, action, sha);
    }

    private Event claim(String type, String action, String sha) {
        var event = queue.claimEvent().orElseThrow();
        assertEquals(PR.scope(), event.scope());
        assertEquals(type, event.eventType());
        assertEquals(action, event.action());
        assertEquals(sha, event.observedHeadSha());
        if (!type.equals("check_run")) assertEquals(PR.number(), event.prNumber());
        return event;
    }

    private void complete(Event event) {
        assertTrue(queue.completeEvent(event, List.of(), this::authorize));
    }

    private String pullWebhook(String action, String sha) {
        return prEnvelope(action, sha, ",\"number\":7");
    }

    private String prEnvelope(String action, String sha, String extra) {
        return """
                {"action":"%s","installation":{"id":101},"repository":{"id":1001},
                 "pull_request":{"number":7,"head":{"sha":"%s"}}%s}
                """.formatted(action, sha, extra);
    }

    private HttpResponse<String> signed(String type, UUID id, String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return send(type, id, body, "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))));
    }

    private HttpResponse<String> send(String type, UUID id, String body, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/webhooks/github"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Delivery", id.toString())
                .header("X-GitHub-Event", type)
                .header("X-Hub-Signature-256", signature)
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return webhookClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path fixtureSecret() {
        try {
            var file = Files.createTempFile("sandbox-webhook", ".secret");
            Files.writeString(file, SECRET);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
