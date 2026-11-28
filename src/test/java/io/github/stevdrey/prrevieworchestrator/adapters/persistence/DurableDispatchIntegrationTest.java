package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DurableDispatchIntegrationTest {
    static final String HEAD = "a".repeat(40);
    static final String NEXT = "b".repeat(40);
    static final RepositoryScope FIRST = new RepositoryScope(101, 1001);
    static final RepositoryScope SECOND = new RepositoryScope(202, 2002);
    static final Intent INTENT = new Intent(7, HEAD, "BASELINE", "fixture", "REQUEST_REVIEW");
    static final Limits LIMITS = new Limits(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(8));
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));
    JdbcTemplate jdbc;
    DataSource dataSource;
    JdbcWorkQueue queue;
    RepositoryAuthorization authorization;
    FixtureGateway gateway;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE queue_transitions, inbox_events, installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (202, 2002, true)");
        RepositoryPolicyFixtures.allowReviews(jdbc, "fixture");
        queue = newQueue();
        authorization = new RepositoryAuthorization(Set.of(FIRST, SECOND), new JdbcInstallationAuthorization(jdbc));
        gateway = new FixtureGateway();
    }

    JdbcWorkQueue newQueue() { return new JdbcWorkQueue(jdbc, new DataSourceTransactionManager(dataSource), LIMITS); }
    DurableDispatcher dispatcher(EventPlanner planner) { return new DurableDispatcher(queue, authorization, planner, gateway); }
    DurableDispatcher dispatcher() { return dispatcher(event -> List.of(INTENT)); }

    @Test
    void concurrentDistinctDeliveriesProduceOneLogicalCommandAndIsolateRepositories() throws Exception {
        for (int i = 0; i < 8; i++) event(FIRST, HEAD);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> dispatcher().processEvent()));
            for (var result : results) assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(8, count("inbox_events", "status = 'PROCESSED'"));
        assertEquals(1, count("outbox_commands", "true"));
        assertEquals(1, count("review_cycles", "true"));
        event(SECOND, HEAD);
        dispatcher().processEvent();
        assertEquals(2, count("outbox_commands", "true"));
        assertEquals(2, count("review_cycles", "true"));
        assertTrue(dispatcher().processCommand());
        assertTrue(dispatcher().processCommand());
        assertEquals(2, gateway.writes.get());
        assertEquals(2, gateway.applied.size());
    }

    @Test
    void pausedRepositoryLeavesQueuedWorkPendingAndNewLimitsVetoTheOriginalSnapshot() {
        event(FIRST, HEAD);
        assertTrue(dispatcher().processEvent());
        assertEquals(1L, jdbc.queryForObject("SELECT repository_policy_version FROM outbox_commands", Long.class));
        var operator = new RepositoryPolicyOperator(new JdbcRepositoryPolicy(jdbc).operatorStore(
                new DataSourceTransactionManager(dataSource)), authorization);
        assertTrue(operator.change(FIRST, 1, 1, Change.PAUSE, null).held());
        assertFalse(dispatcher().processCommand());
        assertEquals(1, count("outbox_commands", "status = 'PENDING' AND attempts = 0"));

        assertTrue(operator.change(FIRST, 1, 2, Change.CONFIGURE, RepositoryReviewPolicy.DEFAULT).held());
        assertFalse(operator.change(FIRST, 1, 3, Change.RESUME, null).held());
        assertTrue(dispatcher().processCommand());
        assertEquals(0, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'CANCELLED' AND last_error_code = 'POLICY_DENIED' AND repository_policy_version = 1"));
    }

    @Test
    void increasingTheLimitCannotRelaxAnOlderReviewCommand() {
        var operator = new RepositoryPolicyOperator(new JdbcRepositoryPolicy(jdbc).operatorStore(
                new DataSourceTransactionManager(dataSource)), authorization);
        var stages = Set.of(Stage.BASELINE, Stage.DEEP);
        var providers = Set.of(INTENT.provider());
        assertEquals(2L, operator.change(FIRST, 1, 1, Change.CONFIGURE,
                new RepositoryReviewPolicy(stages, providers, false, null, 1)).version());
        event(FIRST, HEAD);
        assertTrue(dispatcher().processEvent());
        assertEquals(1, jdbc.update("""
                UPDATE outbox_commands SET available_at = clock_timestamp() + interval '1 hour'
                WHERE command_key = ? AND repository_policy_version = 2
                """, INTENT.key(FIRST)));

        assertEquals(3L, operator.change(FIRST, 1, 2, Change.CONFIGURE,
                new RepositoryReviewPolicy(stages, providers, false, null, 2)).version());
        var newer = new Intent(7, HEAD, "DEEP", INTENT.provider(), INTENT.type());
        event(FIRST, HEAD);
        assertTrue(dispatcher(ignored -> List.of(newer)).processEvent());
        assertTrue(dispatcher().processCommand());
        assertEquals(Set.of(newer.key(FIRST)), gateway.applied);
        assertEquals(1, count("outbox_commands", "status = 'SUCCEEDED' AND repository_policy_version = 3"));

        ready("outbox_commands");
        assertTrue(dispatcher().processCommand());
        assertEquals(Set.of(newer.key(FIRST)), gateway.applied);
        assertEquals(1, count("outbox_commands",
                "status = 'CANCELLED' AND last_error_code = 'POLICY_DENIED' AND repository_policy_version = 2"));
    }

    @Test
    void deferredCommitFailureRollsBackOutboxCycleAndJournalWithInboxCompletion() {
        UUID id = event(FIRST, HEAD);
        Event claimed = queue.claimEvent().orElseThrow();
        jdbc.execute("""
                CREATE FUNCTION reject_dispatch_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.outcome = 'PROCESSED' THEN RAISE EXCEPTION 'synthetic commit failure'; END IF;
                RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_dispatch_commit AFTER INSERT ON queue_transitions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_dispatch_commit()
                """);
        try {
            assertThrows(RuntimeException.class, () -> queue.completeEvent(claimed, List.of(INTENT), () -> authorization.requireAuthorized(FIRST)));
            assertEquals("PROCESSING", status("inbox_events", id));
            assertEquals(0, count("outbox_commands", "true"));
            assertEquals(0, count("review_cycles", "true"));
            assertEquals(0, count("queue_transitions", "outcome = 'PROCESSED'"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_dispatch_commit ON queue_transitions");
            jdbc.execute("DROP FUNCTION reject_dispatch_commit()");
        }
        expire("inbox_events", id);
        queue = newQueue();
        dispatcher().processEvent();
        assertEquals("PROCESSED", status("inbox_events", id));
        assertEquals(1, count("outbox_commands", "true"));
    }

    @Test
    void expiredTokenCannotCompleteOrFailAndRecoveryChangesTokenEvenOnSameWorker() {
        UUID id = event(FIRST, HEAD);
        Event stale = queue.claimEvent().orElseThrow();
        expire("inbox_events", id);
        Event recovered = queue.claimEvent().orElseThrow();
        assertNotEquals(stale.lease().token(), recovered.lease().token());
        assertEquals(2, recovered.lease().attempt());
        assertFalse(queue.completeEvent(stale, List.of(INTENT), () -> fail("stale authority invoked")));
        assertFalse(queue.fail(stale.lease(), ErrorCode.INTERNAL_ERROR, false));
        assertTrue(queue.completeEvent(recovered, List.of(INTENT), () -> authorization.requireAuthorized(FIRST)));
        assertEquals("PROCESSED", status("inbox_events", id));
    }

    @Test
    void crashOnFinalAttemptIsDeadLetteredForBothQueues() {
        UUID id = event(FIRST, HEAD);
        jdbc.update("UPDATE inbox_events SET max_attempts = 1 WHERE id = ?", id);
        queue.claimEvent().orElseThrow();
        expire("inbox_events", id);
        assertTrue(newQueue().claimEvent().isEmpty());
        assertEquals("DEAD", status("inbox_events", id));
        assertEquals(1, count("queue_dead_letters", "last_error_code = 'LEASE_EXHAUSTED'"));
        event(FIRST, HEAD);
        dispatcher().processEvent();
        UUID command = jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        jdbc.update("UPDATE outbox_commands SET max_attempts = 1 WHERE id = ?", command);
        queue.claimCommand().orElseThrow();
        expire("outbox_commands", command);
        assertTrue(newQueue().claimCommand().isEmpty());
        assertEquals("DEAD", status("outbox_commands", command));
        assertEquals(2, count("queue_dead_letters", "last_error_code = 'LEASE_EXHAUSTED'"));
    }

    @Test
    void lostApiResponseIsReconciledAfterRestartWithoutDuplicateWrite() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        gateway.loseResponse = true;
        dispatcher().processCommand();
        assertEquals(1, gateway.writes.get());
        // The effect may have landed, so the retry is recorded as reconciliation-required.
        assertEquals(1, count("outbox_commands", "status = 'PENDING' AND last_error_code = 'REMOTE_UNCERTAIN'"));
        ready("outbox_commands");
        queue = newQueue();
        dispatcher().processCommand();
        assertEquals(1, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'SUCCEEDED' AND attempts = 2"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appliedEffectRemainsSucceededWhenHeadAdvancesBeforeReconciliation(boolean loseResponse) {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        UUID command = jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        if (loseResponse) {
            gateway.loseResponse = true;
        } else {
            // The remote write succeeds, but its terminal status/journal cannot commit locally.
            jdbc.execute("""
                    CREATE FUNCTION reject_command_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF NEW.outcome = 'SUCCEEDED' THEN RAISE EXCEPTION 'synthetic commit failure'; END IF;
                    RETURN NEW; END; $$
                    """);
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_command_completion AFTER INSERT ON queue_transitions
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_command_completion()
                    """);
        }
        try {
            dispatcher().processCommand();
        } finally {
            if (!loseResponse) {
                jdbc.execute("DROP TRIGGER reject_command_completion ON queue_transitions");
                jdbc.execute("DROP FUNCTION reject_command_completion()");
            }
        }
        assertEquals(1, gateway.writes.get());
        assertEquals("PENDING", status("outbox_commands", command));
        assertEquals(1, count("outbox_commands", "last_error_code = 'REMOTE_UNCERTAIN' AND attempts = 1"));

        gateway.head = NEXT;
        queue = newQueue();
        ready("outbox_commands");
        assertTrue(dispatcher().processCommand());

        assertEquals("SUCCEEDED", status("outbox_commands", command));
        assertEquals(1, gateway.writes.get(), "Reconciliation must preserve evidence without repeating the write");
        assertEquals(1, count("outbox_commands", "attempts = 2 AND expected_head_sha = '" + HEAD + "'"));
        assertEquals(INTENT.key(FIRST), jdbc.queryForObject("SELECT command_key FROM outbox_commands WHERE id = ?", String.class, command));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'SUCCEEDED'", Integer.class, command));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'CANCELLED'", Integer.class, command));
        assertEquals(0, count("review_cycles", "head_sha = '" + NEXT + "'"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uncertainEffectRemainsReconcilableUntilAppliedAfterHeadAdvance(boolean headAdvanced) {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        UUID command = jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        gateway.loseResponse = true;
        dispatcher().processCommand();
        assertEquals("PENDING", status("outbox_commands", command));
        assertEquals(1, gateway.writes.get());

        gateway.head = headAdvanced ? NEXT : HEAD;
        gateway.unknown = true;
        queue = newQueue();
        ready("outbox_commands");
        assertTrue(dispatcher().processCommand());

        assertEquals("PENDING", status("outbox_commands", command));
        assertEquals(1, count("outbox_commands", "attempts = 2 AND last_error_code = 'REMOTE_UNCERTAIN'"));
        assertEquals(1, gateway.writes.get(), "Unsettled effects must be reconciled without another write");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'RETRY' AND error_code = 'REMOTE_UNCERTAIN'", Integer.class, command));

        gateway.unknown = false;
        queue = newQueue();
        ready("outbox_commands");
        assertTrue(dispatcher().processCommand());
        assertEquals("SUCCEEDED", status("outbox_commands", command));
        assertEquals(1, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "attempts = 3 AND expected_head_sha = '" + HEAD + "' AND last_error_code IS NULL"));
        assertEquals(INTENT.key(FIRST), jdbc.queryForObject("SELECT command_key FROM outbox_commands WHERE id = ?", String.class, command));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'SUCCEEDED'", Integer.class, command));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'CANCELLED'", Integer.class, command));
        assertEquals(0, count("review_cycles", "head_sha = '" + NEXT + "'"));
    }

    @Test
    void uncertainEffectOnStaleHeadDeadLettersWithUncertaintyAfterRetryCeiling() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        UUID command = jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        gateway.loseResponse = true;
        dispatcher().processCommand();
        gateway.head = NEXT;
        gateway.unknown = true;
        for (int attempt = 2; attempt <= 5; attempt++) {
            queue = newQueue();
            ready("outbox_commands");
            assertTrue(dispatcher().processCommand());
            assertEquals(attempt == 5 ? "DEAD" : "PENDING", status("outbox_commands", command));
            assertEquals("REMOTE_UNCERTAIN", jdbc.queryForObject("SELECT last_error_code FROM outbox_commands WHERE id = ?", String.class, command));
            assertEquals(1, gateway.writes.get());
        }
        assertEquals(1, count("queue_dead_letters", "queue = 'OUTBOX' AND attempts = 5 AND last_error_code = 'REMOTE_UNCERTAIN' AND completed_at IS NOT NULL"));
        assertEquals(INTENT.key(FIRST), jdbc.queryForObject("SELECT command_key FROM outbox_commands WHERE id = ?", String.class, command));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM queue_transitions WHERE work_id = ? AND outcome = 'CANCELLED'", Integer.class, command));
        assertFalse(dispatcher().processCommand());
    }

    @Test
    void crashAfterRemoteAcceptBeforeLocalCommitIsReconciled() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        Command claimed = queue.claimCommand().orElseThrow();
        assertThrows(AssertionError.class, () -> queue.executeCommand(claimed, () -> authorization.requireAuthorized(FIRST), policyAllows -> {
            gateway.execute(claimed);
            throw new AssertionError("simulated process death");
        }));
        assertEquals("PROCESSING", status("outbox_commands", claimed.lease().id()));
        expire("outbox_commands", claimed.lease().id());
        queue = newQueue();
        dispatcher().processCommand();
        assertEquals(1, gateway.writes.get());
        assertEquals("SUCCEEDED", status("outbox_commands", claimed.lease().id()));
    }

    @Test
    void unknownRemoteOutcomeNeverRepeatsEffectAndStopsAtAttemptCeiling() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        gateway.unknown = true;
        for (int i = 0; i < 5; i++) {
            ready("outbox_commands");
            dispatcher().processCommand();
        }
        assertEquals(0, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'DEAD' AND attempts = 5 AND last_error_code = 'REMOTE_UNCERTAIN'"));
        assertFalse(dispatcher().processCommand());
    }

    @Test
    void oldShaIsCancelledEvenWhenDeliveredAfterNewShaAndCannotWrite() {
        gateway.head = NEXT;
        event(FIRST, NEXT);
        dispatcher(event -> List.of(new Intent(7, event.observedHeadSha(), "BASELINE", "fixture", "REQUEST_REVIEW"))).processEvent();
        event(FIRST, HEAD);
        dispatcher().processEvent();
        dispatcher().processCommand();
        dispatcher().processCommand();
        assertEquals(1, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'CANCELLED' AND expected_head_sha = '" + HEAD + "'"));
        assertEquals(1, count("outbox_commands", "status = 'SUCCEEDED' AND expected_head_sha = '" + NEXT + "'"));
    }

    @Test
    void revocationAtPlanningCommitAndBeforeDispatchFailsClosed() {
        event(FIRST, HEAD);
        dispatcher(event -> {
            jdbc.update("UPDATE installations SET status = 'REVOKED' WHERE installation_id = 101");
            return List.of(INTENT);
        }).processEvent();
        assertEquals(0, count("outbox_commands", "true"));
        assertEquals(1, count("inbox_events", "status = 'DEAD' AND last_error_code = 'AUTHORIZATION_DENIED'"));
        jdbc.update("UPDATE installations SET status = 'ACTIVE' WHERE installation_id = 101");
        event(FIRST, HEAD);
        dispatcher().processEvent();
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        dispatcher().processCommand();
        assertEquals(0, gateway.reads.get());
        assertEquals(0, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'DEAD' AND last_error_code = 'AUTHORIZATION_DENIED'"));
    }

    @ParameterizedTest
    // The outbox authorizes once inside its locked transaction; FOR SHARE locks make a second query redundant.
    @CsvSource({"true, 1", "true, 2", "false, 1"})
    void authorizationOutageRetriesAndRecoversAfterRestart(boolean inbox, int failedCheck) {
        UUID eventId = event(FIRST, HEAD);
        if (!inbox) dispatcher().processEvent();
        String table = inbox ? "inbox_events" : "outbox_commands";
        UUID id = inbox ? eventId : jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        var checks = new AtomicInteger();
        var liveMembership = new JdbcInstallationAuthorization(jdbc);
        authorization = new RepositoryAuthorization(Set.of(FIRST), scope -> {
            if (checks.incrementAndGet() == failedCheck) {
                throw new TransientDataAccessResourceException("synthetic-secret-authority-diagnostics");
            }
            return liveMembership.isActiveAndEnabled(scope);
        });

        if (inbox) dispatcher().processEvent(); else dispatcher().processCommand();

        assertEquals(0, gateway.writes.get(), "Uncertain authorization must block effects");
        assertEquals("PENDING", status(table, id));
        assertEquals(1, count(table, "attempts = 1 AND last_error_code = 'AUTHORIZATION_UNAVAILABLE'"));
        assertEquals(1, count("queue_transitions", "outcome = 'RETRY' AND error_code = 'AUTHORIZATION_UNAVAILABLE'"));
        assertTrue(liveMembership.isActiveAndEnabled(FIRST));
        if (inbox) assertEquals(0, count("outbox_commands", "true"));

        // Persisted retry state survives replacement of the queue/dispatcher after the authority recovers.
        queue = newQueue();
        ready(table);
        if (inbox) dispatcher().processEvent();
        dispatcher().processCommand();
        assertEquals(inbox ? "PROCESSED" : "SUCCEEDED", status(table, id));
        assertEquals(1, count(table, "attempts = 2"));
        assertEquals(1, gateway.writes.get());
    }

    @Test
    void persistentAuthorizationOutageStopsAtRetryCeilingWithoutPlanning() {
        event(FIRST, HEAD);
        authorization = new RepositoryAuthorization(Set.of(FIRST), scope -> {
            throw new TransientDataAccessResourceException("synthetic-secret-authority-diagnostics");
        });
        var dispatcher = dispatcher(event -> { fail("Unavailable authority must block planning"); return List.of(); });
        for (int i = 0; i < 5; i++) {
            ready("inbox_events");
            assertTrue(dispatcher.processEvent());
        }
        assertEquals(1, count("queue_dead_letters", "attempts = 5 AND last_error_code = 'AUTHORIZATION_UNAVAILABLE'"));
        assertEquals(0, count("outbox_commands", "true"));
        assertEquals(0, gateway.writes.get());
        assertFalse(dispatcher.processEvent());
    }

    @Test
    void unknownInstallationAndLegacyRouteAreTerminalWithoutBlockingOtherWork() {
        UUID id = event(null, null);
        dispatcher().processEvent();
        assertEquals("DEAD", status("inbox_events", id));
        // A repository-less row is an unsupported route, not an authorization incident.
        assertEquals(1, count("inbox_events", "id = '" + id + "' AND last_error_code = 'INVALID_EVENT'"));
        event(FIRST, null);
        dispatcher(event -> { throw new Failure(ErrorCode.INVALID_EVENT, false); }).processEvent();
        event(SECOND, HEAD);
        dispatcher().processEvent();
        assertEquals(2, count("queue_dead_letters", "queue = 'INBOX'"));
        assertEquals(1, count("inbox_events", "status = 'PROCESSED'"));
    }

    @Test
    void poisonAdapterFailureHasBoundedBackoffAndSanitizedCorrelationLogs() {
        UUID id = event(FIRST, HEAD);
        Logger logger = (Logger) LoggerFactory.getLogger(DurableDispatcher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var dispatcher = dispatcher(event -> { throw new RuntimeException("token=super-secret\nprivate response"); });
            assertTrue(dispatcher.processEvent());
            assertFalse(dispatcher.processEvent());
            assertEquals(1, count("inbox_events", "status = 'PENDING' AND available_at > clock_timestamp() AND attempts = 1"));
            for (int i = 1; i < 5; i++) { ready("inbox_events"); dispatcher.processEvent(); }
            assertEquals("DEAD", status("inbox_events", id));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertTrue(logs.contains("work_id=" + id));
            assertTrue(logs.contains("correlation_id="));
            assertFalse(logs.contains("super-secret"));
            assertFalse(logs.contains("private response"));
            assertEquals(5, count("queue_transitions", "error_code = 'INTERNAL_ERROR'"));
            assertEquals(Duration.ofSeconds(2), LIMITS.backoff(1));
            assertEquals(Duration.ofSeconds(8), LIMITS.backoff(5));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void unsupportedCommandIsDeadBeforeRemoteReconciliation() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        gateway.supported = false;
        dispatcher().processCommand();
        assertEquals(0, gateway.reads.get());
        assertEquals(1, count("queue_dead_letters", "last_error_code = 'UNSUPPORTED_COMMAND'"));
    }

    @Test
    void concurrentDispatchersCannotRunSameEffect() throws Exception {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) results.add(executor.submit(() -> dispatcher().processCommand()));
            for (var result : results) result.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, gateway.writes.get());
        assertEquals(1, count("outbox_commands", "status = 'SUCCEEDED'"));
    }

    @Test
    void leaseExpiringDuringBoundedEffectCannotBeStolen() throws Exception {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        Command command = queue.claimCommand().orElseThrow();
        jdbc.update("UPDATE outbox_commands SET locked_until = clock_timestamp() + interval '1 second' WHERE id = ?", command.lease().id());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> result = executor.submit(() -> queue.executeCommand(command, () -> authorization.requireAuthorized(FIRST), policyAllows -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                gateway.execute(command);
                return Outcome.SUCCEEDED;
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Thread.sleep(1100);
                assertTrue(newQueue().claimCommand().isEmpty());
            } finally { release.countDown(); }
            assertTrue(result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, gateway.writes.get());
    }

    @Test
    void staleOutboxTokenCannotInvokeGateway() {
        event(FIRST, HEAD);
        dispatcher().processEvent();
        Command stale = queue.claimCommand().orElseThrow();
        expire("outbox_commands", stale.lease().id());
        assertFalse(queue.executeCommand(stale, () -> fail("stale authorization"), policyAllows -> { fail("stale effect"); return null; }));
        dispatcher().processCommand();
        assertEquals(1, gateway.writes.get());
    }

    UUID event(RepositoryScope scope, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inbox_events (id, delivery_id, event_type, event_action, installation_id, repository_id,
                    pr_number, head_sha, payload_sha256, correlation_id, verified_at)
                VALUES (?, ?, 'pull_request', 'opened', ?, ?, 7, ?, ?, ?, now())
                """, id, UUID.randomUUID().toString(), scope == null ? 999 : scope.installationId(),
                scope == null ? null : scope.repositoryId(), sha, UUID.randomUUID().toString().replace("-", "").repeat(2), UUID.randomUUID());
        return id;
    }
    int count(String table, String where) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class); }
    String status(String table, UUID id) { return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id); }
    void expire(String table, UUID id) { jdbc.update("UPDATE " + table + " SET locked_until = clock_timestamp() - interval '1 second' WHERE id = ?", id); }
    void ready(String table) { jdbc.update("UPDATE " + table + " SET available_at = clock_timestamp() - interval '1 second' WHERE status = 'PENDING'"); }

    static final class FixtureGateway implements CommandGateway {
        final Set<String> applied = ConcurrentHashMap.newKeySet();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile String head = HEAD;
        boolean loseResponse;
        boolean unknown;
        boolean supported = true;
        public boolean supports(Command command) { return supported; }
        public RemoteState reconcile(Command command) {
            reads.incrementAndGet();
            return new RemoteState(head, unknown ? RemoteEffect.UNKNOWN
                    : applied.contains(command.key()) ? RemoteEffect.APPLIED : RemoteEffect.ABSENT);
        }
        public void execute(Command command) {
            // Synthetic remote boundary with strong idempotency and fresh SHA check.
            if (!head.equals(command.intent().expectedHeadSha())) throw new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
            if (applied.add(command.key())) writes.incrementAndGet();
            if (loseResponse) { loseResponse = false; throw new Failure(ErrorCode.REMOTE_UNAVAILABLE, true); }
        }
    }
}
