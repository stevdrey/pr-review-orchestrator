package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.application.DeliverDomainEvents;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.ReviewLifecycle;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventQueue.Claim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcDomainEventQueueIntegrationTest {
    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(64);
    private static final String FINGERPRINT = "0".repeat(64);
    private static final RepositoryScope SCOPE = new RepositoryScope(101, 1001);
    private static final PullRequestId PR = new PullRequestId(SCOPE, 7);
    private static final UUID SUBSCRIBER = UUID.randomUUID();
    private static final ReviewTransition.Gates ELIGIBLE =
            new ReviewTransition.Gates(true, false, false, true, false, Ci.PASSED);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private RepositoryAuthorization authorization;
    private JdbcReviewStateStore states;
    private ReviewLifecycle lifecycle;
    private JdbcDomainEventQueue queue;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("""
                INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES
                (101, 1001, true), (101, 2002, true), (202, 1001, true)
                """);
        transactions = new DataSourceTransactionManager(source);
        states = new JdbcReviewStateStore(jdbc, transactions);
        authorization = new RepositoryAuthorization(Set.of(SCOPE), new JdbcInstallationAuthorization(jdbc));
        lifecycle = new ReviewLifecycle(states, authorization);
        queue = new JdbcDomainEventQueue(jdbc, transactions);
        lifecycle.create(PR, HEAD);
    }

    @Test
    void fanoutIsScopedAtomicAndDeduplicated() {
        var repositorySubscriber = UUID.randomUUID();
        subscribe(SUBSCRIBER, SCOPE, PR.number());
        subscribe(repositorySubscriber, SCOPE, null);
        subscribe(UUID.randomUUID(), SCOPE, 8);
        subscribe(UUID.randomUUID(), new RepositoryScope(202, 1001), PR.number());
        subscribe(UUID.randomUUID(), new RepositoryScope(101, 2002), PR.number());
        var command = ready();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            states.applyLocked(PR, command, null);
            assertEquals(1, countEvents());
            assertEquals(2, countDeliveries());
            status.setRollbackOnly();
        });
        assertEquals(0, countEvents());
        assertEquals(0, countDeliveries());
        assertEquals(State.NEW, lifecycle.find(PR).orElseThrow().state());

        lifecycle.apply(PR, command);
        var replay = assertThrows(ReviewConflict.class, () -> lifecycle.apply(PR, command));
        assertEquals(ReviewConflict.Code.DUPLICATE_TRANSITION, replay.code());
        assertEquals(1, countEvents());
        assertEquals(2, countDeliveries());
        assertEquals(Set.of(SUBSCRIBER, repositorySubscriber), Set.copyOf(jdbc.queryForList(
                "SELECT subscription_id FROM pr_event_deliveries", UUID.class)));
    }

    @Test
    void publishesWithAnInsertOnlyOutboxWriter() {
        subscribe(SUBSCRIBER, SCOPE, PR.number());
        var command = ready();
        jdbc.execute("CREATE ROLE v19_transition_writer NOLOGIN");
        try {
            jdbc.execute("""
                    GRANT USAGE ON SCHEMA public TO v19_transition_writer;
                    GRANT SELECT, UPDATE ON pr_review_states TO v19_transition_writer;
                    GRANT SELECT, INSERT ON pr_review_transitions TO v19_transition_writer;
                    GRANT SELECT (id, installation_id, repository_id, pr_number, revoked_at)
                        ON pr_event_subscriptions TO v19_transition_writer;
                    GRANT INSERT (id, installation_id, repository_id, pr_number, transition_id)
                        ON pr_domain_events TO v19_transition_writer;
                    GRANT INSERT (subscription_id, event_id)
                        ON pr_event_deliveries TO v19_transition_writer;
                    """);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                authorization.requireAuthorized(PR.scope());
                PostgresLocks.lockPr(jdbc, PR);
                jdbc.execute("SET LOCAL ROLE v19_transition_writer");
                assertFalse(jdbc.queryForObject("""
                        SELECT has_any_column_privilege('pr_domain_events', 'SELECT')
                            OR has_any_column_privilege('pr_event_deliveries', 'SELECT')
                            OR has_column_privilege('pr_event_subscriptions', 'callback_sha256', 'SELECT')
                        """, Boolean.class));
                states.applyLocked(PR, command, null);
            });

            assertEquals(State.WAITING_FOR_REVIEW, lifecycle.find(PR).orElseThrow().state());
            assertEquals(1, countEvents());
            assertEquals(1, countDeliveries());
            assertEquals(command.id(), jdbc.queryForObject("""
                    SELECT e.transition_id FROM pr_domain_events e
                    JOIN pr_event_deliveries d ON d.event_id = e.id
                    WHERE d.subscription_id = ? AND d.status = 'PENDING'
                    """, UUID.class, SUBSCRIBER));
        } finally {
            jdbc.execute("DROP OWNED BY v19_transition_writer");
            jdbc.execute("DROP ROLE v19_transition_writer");
        }
    }

    @Test
    void persistsTypedEventsWithoutSubscribersAndDoesNotReplayToLateRegistrations() {
        lifecycle.apply(PR, ready());
        assertEquals(1, countEvents());
        assertEquals(0, countDeliveries());
        subscribe(SUBSCRIBER, SCOPE, PR.number());
        var headChange = new ReviewTransition(UUID.randomUUID(), 1, HEAD, UPDATE_HEAD, NEXT_HEAD, ELIGIBLE);
        lifecycle.apply(PR, headChange);
        var check = new CiEvidence.Check(1, "build");
        lifecycle.reconcileCi(2, new CiEvidence(UUID.randomUUID(), PR, NEXT_HEAD, Set.of(check),
                List.of(new CiEvidence.Run(check, NEXT_HEAD, Ci.FAILED)), true));
        assertEquals(3, countEvents());
        assertEquals(2, countDeliveries());

        var events = new ArrayList<PrDomainEvent>();
        var worker = new DeliverDomainEvents(queue, authorization, (id, fingerprint, event) -> {
            assertEquals(SUBSCRIBER, id);
            assertEquals(FINGERPRINT, fingerprint);
            events.add(event);
            return ACKNOWLEDGED;
        });
        worker.processNext();
        worker.processNext();
        worker.processNext();
        assertEquals(List.of(2L, 3L), events.stream().map(PrDomainEvent::stateVersion).sorted().toList());
        var headUpdated = events.stream().filter(event -> event.action() == UPDATE_HEAD).findFirst().orElseThrow();
        assertEquals(PR, headUpdated.pr());
        assertEquals(NEXT_HEAD, headUpdated.headSha());
        assertEquals(headChange.id(), headUpdated.traceId());
        assertEquals("pr.review.transitioned", headUpdated.name());
        assertNotEquals(headUpdated.traceId(), headUpdated.eventId());
        var failedCi = events.stream().filter(event -> event.action() == RECONCILE_CI).findFirst().orElseThrow();
        assertEquals(Ci.FAILED, failedCi.ci());
        assertEquals("pr.ci.failed", failedCi.name());
    }

    @Test
    void restartAndAmbiguousReceiptRetryExactlyTheSameEventAndFenceOldClaims() {
        subscribe(SUBSCRIBER, SCOPE, PR.number());
        lifecycle.apply(PR, ready());
        var first = queue.claim().orElseThrow();
        var events = new ArrayList<PrDomainEvent>();
        // An ambiguous outcome leaves a durable retry, never a second event or delivery row.
        queue.deliver(first, authorization::requireAuthorized, (id, fingerprint, event) -> {
            events.add(event);
            return RETRY;
        });
        assertDelivery("PENDING", 1, "RETRY");
        assertTrue(queue.claim().isEmpty());

        expire();
        var restarted = new JdbcDomainEventQueue(jdbc, transactions);
        var second = restarted.claim().orElseThrow();
        assertEquals(first.eventId(), second.eventId());
        assertEquals(first.subscriptionId(), second.subscriptionId());
        assertEquals(2, second.attempt());
        assertTrue(restarted.claim().isEmpty());
        restarted.deliver(first, authorization::requireAuthorized, (id, fingerprint, event) -> fail("stale claim"));
        restarted.deliver(second, authorization::requireAuthorized, (id, fingerprint, event) -> {
            events.add(event);
            return ACKNOWLEDGED;
        });
        restarted.deliver(second, authorization::requireAuthorized, (id, fingerprint, event) -> fail("settled claim"));
        assertEquals(List.of(events.getFirst(), events.getFirst()), events);
        assertDelivery("DELIVERED", 2, "ACKNOWLEDGED");
    }

    @Test
    void acknowledgedPostWithFailedCommitKeepsTheSameDeliveryKeyOnRecovery() {
        subscribe(SUBSCRIBER, SCOPE, null);
        lifecycle.apply(PR, ready());
        var first = queue.claim().orElseThrow();
        var sent = new ArrayList<PrDomainEvent>();
        jdbc.execute("""
                CREATE FUNCTION fail_event_receipt_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'fixture commit failure';
                END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER fail_receipt_commit AFTER UPDATE ON pr_event_deliveries
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'DELIVERED')
                    EXECUTE FUNCTION fail_event_receipt_commit()
                    """);
            var failure = assertThrows(IllegalStateException.class, () ->
                    queue.deliver(first, authorization::requireAuthorized, (id, fingerprint, event) -> {
                        sent.add(event);
                        return ACKNOWLEDGED;
                    }));
            assertEquals("PR domain event queue unavailable", failure.getMessage());
            assertNull(failure.getCause());
            assertDelivery("PENDING", 1, "IN_FLIGHT");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_receipt_commit ON pr_event_deliveries");
            jdbc.execute("DROP FUNCTION fail_event_receipt_commit()");
        }

        expire();
        var retry = queue.claim().orElseThrow();
        assertEquals(first.subscriptionId(), retry.subscriptionId());
        assertEquals(first.eventId(), retry.eventId());
        queue.deliver(retry, authorization::requireAuthorized, (id, fingerprint, event) -> {
            assertEquals(sent.getFirst(), event);
            return ACKNOWLEDGED;
        });
        assertDelivery("DELIVERED", 2, "ACKNOWLEDGED");
    }

    @Test
    void committedRevocationSuppressesAnAlreadyClaimedRetryAndFutureEvents() {
        subscribe(SUBSCRIBER, SCOPE, null);
        lifecycle.apply(PR, ready());
        var first = queue.claim().orElseThrow();
        queue.deliver(first, authorization::requireAuthorized, (id, fingerprint, event) -> RETRY);
        expire();
        Claim retry = queue.claim().orElseThrow();
        revokeSubscription();
        queue.deliver(retry, authorization::requireAuthorized, (id, fingerprint, event) -> fail("revoked subscription"));
        assertDelivery("CANCELLED", 2, "REVOKED");

        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), 1, HEAD, UPDATE_HEAD, NEXT_HEAD, ELIGIBLE));
        assertEquals(2, countEvents());
        assertEquals(1, countDeliveries());
        assertTrue(queue.claim().isEmpty());
    }

    @Test
    void revocationWaitsForInFlightDeliveryAndAnotherWorkerCannotClaimIt() throws Exception {
        subscribe(SUBSCRIBER, SCOPE, null);
        lifecycle.apply(PR, ready());
        var first = queue.claim().orElseThrow();
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoking = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var send = workers.submit(() -> queue.deliver(first, authorization::requireAuthorized, (id, hash, event) -> {
                sending.countDown();
                await(release);
                return RETRY;
            }));
            try {
                await(sending);
                assertTrue(queue.claim().isEmpty());
                var revoke = workers.submit(() -> {
                    revoking.countDown();
                    revokeSubscription();
                });
                await(revoking);
                assertThrows(TimeoutException.class, () -> revoke.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                send.get(3, TimeUnit.SECONDS);
                revoke.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        expire();
        queue.deliver(queue.claim().orElseThrow(), authorization::requireAuthorized,
                (id, hash, event) -> fail("delivery after revocation"));
        assertDelivery("CANCELLED", 2, "REVOKED");
    }

    @Test
    void subscriptionIdentityCannotBeReboundOrReactivated() {
        subscribe(SUBSCRIBER, SCOPE, null);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE pr_event_subscriptions SET pr_number = 7 WHERE id = ?", SUBSCRIBER));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE pr_event_subscriptions SET callback_sha256 = ? WHERE id = ?", "a".repeat(64), SUBSCRIBER));
        revokeSubscription();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE pr_event_subscriptions SET revoked_at = NULL WHERE id = ?", SUBSCRIBER));
    }

    @Test
    void repositoryAuthorizationIsRecheckedAfterClaim() {
        subscribe(SUBSCRIBER, SCOPE, null);
        lifecycle.apply(PR, ready());
        var first = queue.claim().orElseThrow();
        var unavailable = new RepositoryAuthorization(Set.of(SCOPE), scope -> { throw new IllegalStateException(); });
        queue.deliver(first, unavailable::requireAuthorized, (id, fingerprint, event) -> fail("authority unavailable"));
        assertDelivery("PENDING", 1, "AUTHORIZATION_UNAVAILABLE");
        expire();
        // A failing authority query aborts the transaction; the outcome must still be recorded.
        var sqlFailure = queue.claim().orElseThrow();
        var failingSql = new RepositoryAuthorization(Set.of(SCOPE),
                scope -> jdbc.queryForObject("SELECT 1 / 0", Integer.class) == 1);
        queue.deliver(sqlFailure, failingSql::requireAuthorized, (id, fingerprint, event) -> fail("query failed"));
        assertDelivery("PENDING", 2, "AUTHORIZATION_UNAVAILABLE");
        expire();
        var misconfigured = queue.claim().orElseThrow();
        queue.deliver(misconfigured, authorization::requireAuthorized, (id, fingerprint, event) -> MISCONFIGURED);
        assertDelivery("PENDING", 3, "CONFIGURATION_MISSING");
        expire();
        var retry = queue.claim().orElseThrow();
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE repository_id = 1001");
        queue.deliver(retry, authorization::requireAuthorized, (id, fingerprint, event) -> fail("repository disabled"));
        assertDelivery("CANCELLED", 4, "AUTHORIZATION_DENIED");
    }

    @Test
    void lostWorkersConsumeADurableAttemptAndTheFinalExpiredLeaseIsTerminal() {
        subscribe(SUBSCRIBER, SCOPE, null);
        lifecycle.apply(PR, ready());
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(attempt, queue.claim().orElseThrow().attempt());
            expire();
        }
        assertTrue(queue.claim().isEmpty());
        assertDelivery("DEAD", 5, "LEASE_EXPIRED");
        expire();
        assertTrue(queue.claim().isEmpty());
    }

    private ReviewTransition ready() {
        return new ReviewTransition(UUID.randomUUID(), 0, HEAD, MARK_READY, null, ELIGIBLE);
    }

    private void subscribe(UUID id, RepositoryScope scope, Integer prNumber) {
        jdbc.update("""
                INSERT INTO pr_event_subscriptions (id, installation_id, repository_id, pr_number, callback_sha256)
                VALUES (?, ?, ?, ?, ?)
                """, id, scope.installationId(), scope.repositoryId(), prNumber, FINGERPRINT);
    }

    private void revokeSubscription() {
        assertEquals(1, jdbc.update("UPDATE pr_event_subscriptions SET revoked_at = clock_timestamp() WHERE id = ?", SUBSCRIBER));
    }

    private void expire() {
        jdbc.update("UPDATE pr_event_deliveries SET available_at = clock_timestamp() - interval '1 second'");
    }

    private int countEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM pr_domain_events", Integer.class);
    }

    private int countDeliveries() {
        return jdbc.queryForObject("SELECT count(*) FROM pr_event_deliveries", Integer.class);
    }

    private void assertDelivery(String status, int attempts, String outcome) {
        var row = jdbc.queryForMap("SELECT status, attempts, last_outcome FROM pr_event_deliveries");
        assertEquals(status, row.get("status"));
        assertEquals(attempts, row.get("attempts"));
        assertEquals(outcome, row.get("last_outcome"));
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
