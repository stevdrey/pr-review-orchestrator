package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.adapters.operator.RepositoryPolicyCommand;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryPolicyOperator;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Intent;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Lease;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Limits;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Queue;
import java.time.Duration;
import java.util.UUID;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.QuietHours;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore.Receipt;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore.Reservation;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RepositoryPolicyIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("test.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final RepositoryScope SCOPE = new RepositoryScope(101, 1001);
    private static final RepositoryScope OTHER = new RepositoryScope(101, 1002);
    private static final long ACTOR = 42;
    private static final RepositoryReviewPolicy ENABLED =
            new RepositoryReviewPolicy(Set.of(Stage.BASELINE), Set.of("codex"), false, null, 6);

    private JdbcTemplate jdbc;
    private RepositoryPolicyOperator operator;
    private RepositoryAuthorization authorization;
    private JdbcReviewRequestStore requests;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (101, 1002, true)");
        jdbc.update("""
                INSERT INTO repository_review_policy_operators (installation_id, repository_id, database_role, actor_id)
                VALUES (101, 1001, session_user, 42), (101, 1002, 'different_login', 42)
                """);
        authorization = new RepositoryAuthorization(Set.of(SCOPE, OTHER), new JdbcInstallationAuthorization(jdbc));
        var transactions = new DataSourceTransactionManager(source);
        operator = new RepositoryPolicyOperator(new JdbcRepositoryPolicy(jdbc).operatorStore(transactions), authorization);
        requests = new JdbcReviewRequestStore(jdbc, transactions);
    }

    @Test
    void defaultDeniesAndOnlyTheScopedLoginAndActorMayChangePolicy() {
        var defaults = operator.read(SCOPE, ACTOR);
        assertEquals(0, defaults.version());
        assertEquals(RepositoryReviewPolicy.DEFAULT, defaults.policy());
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "BASELINE", "codex"))).code());
        assertThrows(AuthorizationDenied.class, () -> operator.change(SCOPE, 43, 0, CONFIGURE, ENABLED));
        assertThrows(AuthorizationDenied.class, () -> operator.change(OTHER, ACTOR, 0, PAUSE, null));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO repository_review_policy_versions (installation_id, repository_id, version, stages, providers,
                    require_passing_ci, iteration_cap, held, changed_by, change_kind)
                VALUES (101, 1002, 1, ARRAY['BASELINE'], ARRAY['codex'], false, 6, false, 42, 'CONFIGURE')
                """));
        assertEquals(1, operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED).version());
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "DEEP", "codex"))).code());
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "BASELINE", "claude"))).code());
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE repository_id = 1001");
        assertThrows(AuthorizationDenied.class, () -> operator.change(SCOPE, ACTOR, 1, PAUSE, null));
        jdbc.update("UPDATE installation_repositories SET enabled = true WHERE repository_id = 1001");
        jdbc.update("DELETE FROM repository_review_policy_operators WHERE repository_id = 1001");
        assertThrows(AuthorizationDenied.class, () -> operator.change(SCOPE, ACTOR, 1, PAUSE, null));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM repository_review_policy_versions", Integer.class));
    }

    @Test
    void localCommandUsesTheProvisionedLoginWithoutMigrationPrivileges() {
        jdbc.update("""
                INSERT INTO repository_review_policy_operators (installation_id, repository_id, database_role, actor_id)
                VALUES (101, 1002, session_user, 42)
                """);
        assertEquals(1L, operator.change(OTHER, ACTOR, 0, CONFIGURE, ENABLED).version());
        jdbc.execute("CREATE ROLE issue27_operator LOGIN PASSWORD 'policy-fixture-only'");
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO issue27_operator");
            jdbc.execute("""
                    GRANT EXECUTE ON FUNCTION read_repository_review_policy(bigint, bigint, bigint, boolean)
                    TO issue27_operator
                    """);
            jdbc.execute("""
                    GRANT EXECUTE ON FUNCTION append_repository_review_policy(
                        bigint, bigint, bigint, text[], text[], boolean, text, integer, integer, integer, boolean, bigint, text
                    ) TO issue27_operator
                    """);
            jdbc.update("""
                    INSERT INTO repository_review_policy_operators (installation_id, repository_id, database_role, actor_id)
                    VALUES (101, 1001, 'issue27_operator', 52)
                    """);
            var operatorJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), "issue27_operator", "policy-fixture-only"));
            for (String table : List.of("repository_review_policy_versions", "repository_review_policy_operators",
                    "installations", "installation_repositories")) {
                assertSqlState("42501", () -> operatorJdbc.queryForList("SELECT * FROM " + table));
            }
            assertEquals(List.of(), operatorJdbc.queryForList("SELECT version FROM read_repository_review_policy(101, 1001, 52, false)"));
            assertSqlState("42501", () -> operatorJdbc.queryForList(
                    "SELECT version FROM read_repository_review_policy(101, 1002, 42, false)"));
            assertSqlState("42501", () -> operatorJdbc.queryForList("""
                    SELECT append_repository_review_policy(101, 1002, 2, ARRAY['BASELINE'], ARRAY['codex'], true,
                        NULL, NULL, NULL, 3, false, 42, 'CONFIGURE')
                    """));
            var settings = Map.of("spring.datasource.url", POSTGRES.getJdbcUrl(),
                    "spring.datasource.username", "issue27_operator", "spring.datasource.password", "policy-fixture-only",
                    "orchestrator.github.repositories.101[0]", "1001", "orchestrator.github.repositories.101[1]", "1002",
                    "orchestrator.repository-policy.operator.installation-id", "101",
                    "orchestrator.repository-policy.operator.repository-id", "1001",
                    "orchestrator.repository-policy.operator.actor-id", "52");
            var previous = new HashMap<String, String>();
            settings.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
            try {
                assertEquals(0, RepositoryPolicyCommand.run(new String[]{"show"}));
                assertEquals(0, RepositoryPolicyCommand.run(new String[]{"configure", "0", "3", "true", "BASELINE", "codex"}));
                assertEquals(0, RepositoryPolicyCommand.run(new String[]{"pause", "1"}));
                assertEquals(1, RepositoryPolicyCommand.run(new String[]{"resume", "1"}));
                assertEquals(0, RepositoryPolicyCommand.run(new String[]{"resume", "2"}));
                assertEquals(List.of(3L), operatorJdbc.queryForList(
                        "SELECT version FROM read_repository_review_policy(101, 1001, 52, false)", Long.class));
                System.setProperty("orchestrator.repository-policy.operator.repository-id", "1002");
                assertEquals(1, RepositoryPolicyCommand.run(new String[]{"show"}));
                assertEquals(1, RepositoryPolicyCommand.run(new String[]{"pause", "1"}));
                jdbc.update("DELETE FROM repository_review_policy_operators WHERE database_role = 'issue27_operator'");
                assertSqlState("42501", () -> operatorJdbc.queryForList(
                        "SELECT version FROM read_repository_review_policy(101, 1001, 52, false)"));
            } finally {
                previous.forEach((key, value) -> {
                    if (value == null) System.clearProperty(key);
                    else System.setProperty(key, value);
                });
            }
            assertEquals(List.of(52L, 52L, 52L), jdbc.queryForList(
                    "SELECT changed_by FROM repository_review_policy_versions WHERE repository_id = 1001 ORDER BY version",
                    Long.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM repository_review_policy_versions WHERE repository_id = 1002", Integer.class));
        } finally {
            jdbc.execute("DROP OWNED BY issue27_operator");
            jdbc.execute("DROP ROLE issue27_operator");
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"codex,claude|UTC|23514", "codex|America/Unsupported_Zone|23503"}, delimiter = '|')
    void invalidPolicyValuesCannotBecomeAnImmutableVersion(String provider, String zone, String sqlState) {
        assertSqlState(sqlState, () -> jdbc.update("""
                INSERT INTO repository_review_policy_versions (installation_id, repository_id, version, stages, providers,
                    require_passing_ci, quiet_zone, quiet_start_minute, quiet_end_minute,
                    iteration_cap, held, changed_by, change_kind)
                VALUES (101, 1001, 1, ARRAY['BASELINE'], ?::text[], true, ?, 0, 60, 2, false, 42, 'CONFIGURE')
                """, new String[]{provider}, zone));
        assertEquals(0L, operator.read(SCOPE, ACTOR).version());
        assertEquals(1L, operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED).version());
    }

    @Test
    void compareAndAppendRejectsTheLosingEdit() throws Exception {
        operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> editAfter(start, PAUSE));
            var second = executor.submit(() -> editAfter(start, CONFIGURE));
            start.countDown();
            assertEquals(Set.of("APPLIED", "STALE_VERSION"), Set.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)));
        }
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT version FROM repository_review_policy_versions ORDER BY version", Long.class));
        assertEquals(List.of(42L, 42L), jdbc.queryForList("SELECT changed_by FROM repository_review_policy_versions ORDER BY version", Long.class));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE repository_review_policy_versions SET held = true WHERE version = 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM repository_review_policy_versions WHERE version = 1"));
    }

    @Test
    void pauseWaitsForTheSendFenceAndResumePreservesAuditAndReservations() throws Exception {
        operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED);
        var alreadySending = request(7, "BASELINE", "codex");
        var pending = request(8, "BASELINE", "codex");
        var first = reserve(alreadySending);
        var second = reserve(pending);
        var effectEntered = new CountDownLatch(1);
        var letEffectReturn = new CountDownLatch(1);
        var pauseStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var send = executor.submit(() -> requests.send(alreadySending, first.id(), this::authorize, guards -> {
                guards.run();
                effectEntered.countDown();
                await(letEffectReturn);
                return new Receipt(9001, true);
            }));
            try {
                assertTrue(effectEntered.await(5, TimeUnit.SECONDS));
                var pause = executor.submit(() -> {
                    pauseStarted.countDown();
                    return operator.change(SCOPE, ACTOR, 1, PAUSE, null);
                });
                assertTrue(pauseStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pause.get(150, TimeUnit.MILLISECONDS));
                letEffectReturn.countDown();
                assertTrue(pause.get(5, TimeUnit.SECONDS).held());
                send.get(5, TimeUnit.SECONDS);
            } finally {
                letEffectReturn.countDown();
            }
        }
        assertEquals(ReviewRequestFailure.Code.HUMAN_INTERVENTION_REQUIRED, assertThrows(ReviewRequestFailure.class,
                () -> requests.send(pending, second.id(), this::authorize, guards -> fail("paused send"))).code());
        var lowered = new RepositoryReviewPolicy(ENABLED.stages(), ENABLED.providers(), false, null, 1);
        assertTrue(operator.change(SCOPE, ACTOR, 2, CONFIGURE, lowered).held());
        assertFalse(operator.change(SCOPE, ACTOR, 3, RESUME, null).held());
        assertEquals(lowered, operator.read(SCOPE, ACTOR).policy());
        assertEquals(List.of("CONFIGURE", "PAUSE", "CONFIGURE", "RESUME"), jdbc.queryForList(
                "SELECT change_kind FROM repository_review_policy_versions ORDER BY version", String.class));
        assertEquals(List.of("COMMENT_CREATED", "NOT_SENT"), jdbc.queryForList(
                "SELECT state FROM review_requests ORDER BY pr_number", String.class));
        assertEquals(List.of(1L, 1L), jdbc.queryForList("SELECT repository_policy_version FROM review_requests ORDER BY pr_number", Long.class));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE review_requests SET repository_policy_version = 4 WHERE id = ?", first.id()));
        assertFalse(reserve(pending).created());
        assertTrue(reserve(request(9, "BASELINE", "codex")).created());
    }

    @Test
    void aStricterLiveCiGateStopsAReservationBeforeAnyEffect() {
        operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED);
        var request = request(7, "BASELINE", "codex");
        var ticket = reserve(request);
        operator.change(SCOPE, ACTOR, 1, CONFIGURE,
                new RepositoryReviewPolicy(ENABLED.stages(), ENABLED.providers(), true, null, 6));
        assertEquals(ReviewRequestFailure.Code.CI_NOT_PASSED, assertThrows(ReviewRequestFailure.class,
                () -> requests.send(request, ticket.id(), this::authorize, guards -> fail("CI not established"))).code());
        assertEquals(1L, jdbc.queryForObject("SELECT repository_policy_version FROM review_requests", Long.class));
    }

    @Test
    void quietHoursUseLocalTimeAndRejectAmbiguousConfiguration() {
        var registeredZones = jdbc.queryForList("SELECT zone_id FROM repository_review_policy_time_zones", String.class);
        assertFalse(registeredZones.isEmpty());
        assertTrue(ZoneId.getAvailableZoneIds().containsAll(registeredZones));
        var quiet = new QuietHours(ZoneId.of("America/Costa_Rica"), 22 * 60, 6 * 60);
        var policy = new RepositoryReviewPolicy(ENABLED.stages(), ENABLED.providers(), true, quiet, 2);
        var snapshot = operator.change(SCOPE, ACTOR, 0, CONFIGURE, policy);
        assertEquals(snapshot, operator.read(SCOPE, ACTOR));
        assertFalse(policy.permits("BASELINE", "codex", Instant.parse("2026-11-27T04:00:00Z")));
        assertFalse(policy.permits("BASELINE", "codex", Instant.parse("2026-11-27T11:59:59Z")));
        assertTrue(policy.permits("BASELINE", "codex", Instant.parse("2026-11-27T12:00:00Z")));
        assertTrue(policy.permits("BASELINE", "codex", Instant.parse("2026-11-28T03:59:59Z")));
        assertThrows(IllegalArgumentException.class, () -> new QuietHours(ZoneId.of("UTC"), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryReviewPolicy(Set.of(Stage.BASELINE),
                Set.of("codex"), true, null, 7));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryReviewPolicy(Set.of(Stage.BASELINE),
                Set.of("Invalid Provider"), true, null, 1));
    }

    @Test
    void anUnrecognizedStoredZoneFailsClosedAndCanBeReplaced() {
        jdbc.update("INSERT INTO repository_review_policy_time_zones (zone_id) VALUES ('Mars/Olympus')");
        jdbc.update("""
                INSERT INTO repository_review_policy_versions (installation_id, repository_id, version, stages, providers,
                    require_passing_ci, quiet_zone, quiet_start_minute, quiet_end_minute,
                    iteration_cap, held, changed_by, change_kind)
                VALUES (101, 1001, 1, ARRAY['BASELINE'], ARRAY['codex'], false, 'Mars/Olympus', 0, 60, 6, false, 42, 'CONFIGURE')
                """);
        var unreadable = operator.read(SCOPE, ACTOR);
        assertEquals(1L, unreadable.version());
        assertEquals(RepositoryReviewPolicy.DEFAULT, unreadable.policy());
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "BASELINE", "codex"))).code());
        assertEquals(2L, operator.change(SCOPE, ACTOR, 1, CONFIGURE, ENABLED).version());
        assertTrue(reserve(request(7, "BASELINE", "codex")).created());
    }

    @Test
    void quietHoursDeferNativeRequestsInsteadOfDenyingThem() {
        var quiet = new QuietHours(ZoneId.of("America/Costa_Rica"), 22 * 60, 6 * 60);
        assertEquals(Instant.parse("2026-11-27T12:00:00Z"), quiet.end(Instant.parse("2026-11-27T04:00:00Z")));
        assertEquals(Instant.parse("2026-11-28T12:00:00Z"), quiet.end(Instant.parse("2026-11-27T12:00:00Z")));
        int minute = jdbc.queryForObject(
                "SELECT (extract(hour FROM clock_timestamp() AT TIME ZONE 'UTC') * 60 + extract(minute FROM clock_timestamp() AT TIME ZONE 'UTC'))::int",
                Integer.class);
        operator.change(SCOPE, ACTOR, 0, CONFIGURE, new RepositoryReviewPolicy(ENABLED.stages(), ENABLED.providers(),
                false, new QuietHours(ZoneId.of("UTC"), minute, (minute + 120) % 1440), 6));
        assertEquals(ReviewRequestFailure.Code.QUIET_HOURS, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "BASELINE", "codex"))).code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_requests", Integer.class));
    }

    @Test
    void tentativeSlotsOrALoweredCapDenyWithoutStoppingThePr() {
        var pr = new PullRequestId(SCOPE, 7);
        new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()))
                .create(pr, "a".repeat(40), this::authorize);
        operator.change(SCOPE, ACTOR, 0, CONFIGURE,
                new RepositoryReviewPolicy(Set.of(Stage.BASELINE, Stage.DEEP), Set.of("codex"), false, null, 1));
        assertTrue(reserve(request(7, "BASELINE", "codex")).created());
        assertEquals(ReviewRequestFailure.Code.LIMIT_REACHED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "DEEP", "codex"))).code());
        assertNotEquals("PAUSED", jdbc.queryForObject("SELECT state FROM pr_review_states WHERE pr_number = 7", String.class));
    }

    @Test
    void aGenericCommandSendingNativelyIsNotItsOwnDuplicate() {
        var snapshot = operator.change(SCOPE, ACTOR, 0, CONFIGURE, ENABLED);
        var lease = new Lease(Queue.INBOX, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        var queue = new JdbcWorkQueue(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()),
                new Limits(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(8)));
        queue.enqueue(new Event(lease, SCOPE, "pull_request", "synchronize", 7, "a".repeat(40)),
                new Intent(7, "a".repeat(40), "BASELINE", "codex", "REQUEST_REVIEW"), null, snapshot.version());
        UUID command = jdbc.queryForObject("SELECT id FROM outbox_commands", UUID.class);
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, assertThrows(ReviewRequestFailure.class,
                () -> reserve(request(7, "BASELINE", "codex"))).code());
        assertTrue(reserve(new ReviewRequest(new PullRequestId(SCOPE, 7), "a".repeat(40), "BASELINE", "codex",
                ReviewRequest.Trigger.PR_READY, ACTOR, false, 0, null, command)).created());
    }

    private void assertSqlState(String state, Runnable operation) {
        var failure = assertThrows(DataAccessException.class, operation::run);
        assertEquals(state, assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getSQLState());
    }

    private String editAfter(CountDownLatch start, Change change) throws InterruptedException {
        start.await();
        try {
            operator.change(SCOPE, ACTOR, 1, change, change == CONFIGURE ? ENABLED : null);
            return "APPLIED";
        } catch (ReviewConflict conflict) {
            return conflict.code().name();
        }
    }

    private ReviewRequest request(int number, String stage, String provider) {
        return new ReviewRequest(new PullRequestId(SCOPE, number), "a".repeat(40), stage, provider,
                ReviewRequest.Trigger.PR_READY, ACTOR, false);
    }

    private Reservation reserve(ReviewRequest request) {
        return requests.reserve(request, ACTOR, 6, this::authorize, () -> {});
    }

    private void authorize() {
        authorization.requireAuthorized(SCOPE);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
