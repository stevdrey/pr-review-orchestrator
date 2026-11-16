package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReviewStateIntegrationTest {
    static final String HEAD = "a".repeat(40);
    static final String NEXT = "b".repeat(40);
    static final PullRequestId FIRST = new PullRequestId(new RepositoryScope(101, 1001), 7);
    static final PullRequestId SECOND = new PullRequestId(new RepositoryScope(202, 2002), 7);
    static final Gates CLEAR = new Gates(true, false, false, true, false, Ci.PASSED);
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));
    DataSource dataSource;
    JdbcTemplate jdbc;
    ReviewLifecycle lifecycle;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (202, 2002, true)");
        lifecycle = newLifecycle();
    }

    ReviewLifecycle newLifecycle() {
        return new ReviewLifecycle(new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(dataSource)),
                new RepositoryAuthorization(Set.of(FIRST.scope(), SECOND.scope()), new JdbcInstallationAuthorization(jdbc)));
    }

    static ReviewTransition command(PullRequestReview state, Action action) {
        Gates gates = action == BLOCKING_FINDINGS ? new Gates(true, false, false, true, true, Ci.PASSED) : CLEAR;
        return new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action,
                action == UPDATE_HEAD ? NEXT : null, gates);
    }

    PullRequestReview step(PullRequestReview state, Action action) {
        return lifecycle.apply(state.pr(), command(state, action));
    }

    PullRequestReview reviewing(PullRequestId pr) {
        return step(step(lifecycle.create(pr, HEAD), MARK_READY), START_REVIEW);
    }

    @Test
    void fullCycleAndPauseResumePersistAcrossAdapterRestart() {
        var state = reviewing(FIRST);
        state = step(state, BLOCKING_FINDINGS);
        state = step(state, AWAIT_FIX);
        state = step(state, UPDATE_HEAD);
        state = step(state, START_REVIEW);
        state = step(state, BASELINE_CLEAN);
        state = step(state, DEEP_CLEAN);
        state = step(state, MARK_READY_FOR_HUMAN);
        state = step(state, PAUSE);
        lifecycle = newLifecycle();
        assertEquals(state, lifecycle.find(FIRST).orElseThrow());
        state = step(state, RESUME);
        assertEquals(READY_FOR_HUMAN, state.state());
        assertEquals(NEXT, state.deepCleanSha());
        assertEquals(state.version(), journalCount());
        assertEquals(state, newLifecycle().find(FIRST).orElseThrow());
    }

    @Test
    void headUpdateClearsBothVerdictsEvenWhilePausedAndRejectsLateResult() {
        var state = step(step(reviewing(FIRST), BASELINE_CLEAN), DEEP_CLEAN);
        state = step(state, MARK_READY_FOR_HUMAN);
        state = step(state, PAUSE);
        var changed = step(state, UPDATE_HEAD);
        assertEquals(PAUSED, changed.state());
        assertNull(changed.baselineCleanSha());
        assertNull(changed.deepCleanSha());
        var resumed = step(changed, RESUME);
        assertEquals(WAITING_FOR_REVIEW, resumed.state());
        var late = new ReviewTransition(UUID.randomUUID(), resumed.version(), HEAD, BASELINE_CLEAN, null, CLEAR);
        assertCode(STALE_HEAD, () -> lifecycle.apply(FIRST, late));
        assertEquals(resumed, newLifecycle().find(FIRST).orElseThrow());
    }

    @Test
    void duplicateIdsAndIllegalTransitionsLeaveNoWrites() {
        var initial = lifecycle.create(FIRST, HEAD);
        var request = command(initial, MARK_READY);
        var ready = lifecycle.apply(FIRST, request);
        assertCode(DUPLICATE_TRANSITION, () -> lifecycle.apply(FIRST, request));
        assertCode(ILLEGAL_TRANSITION, () -> step(ready, MARK_READY));
        assertCode(ALREADY_EXISTS, () -> lifecycle.create(FIRST, NEXT));
        var reviewing = step(ready, START_REVIEW);
        assertCode(DUPLICATE_TRANSITION, () -> lifecycle.apply(FIRST, request));
        assertEquals(2, journalCount());
        assertEquals(reviewing, lifecycle.find(FIRST).orElseThrow());
    }

    @Test
    void reusedTransitionIdForDifferentCommandIsNotReportedAsCommitted() {
        var ready = step(lifecycle.create(FIRST, HEAD), MARK_READY);
        var pause = command(ready, PAUSE);
        var paused = lifecycle.apply(FIRST, pause);
        var blocking = new Gates(true, false, false, true, true, Ci.PASSED);
        assertCode(TRANSITION_ID_REUSED, () -> lifecycle.apply(FIRST,
                new ReviewTransition(pause.id(), paused.version(), HEAD, BLOCKING_FINDINGS, null, blocking)));
        assertCode(TRANSITION_ID_REUSED, () -> lifecycle.apply(FIRST,
                new ReviewTransition(pause.id(), paused.version(), HEAD, PAUSE, null, CLEAR)));
        assertCode(TRANSITION_ID_REUSED, () -> lifecycle.apply(FIRST,
                new ReviewTransition(pause.id(), pause.expectedVersion(), HEAD, UPDATE_HEAD, NEXT, CLEAR)));
        assertCode(DUPLICATE_TRANSITION, () -> lifecycle.apply(FIRST, pause));
        assertEquals(2, journalCount());
        assertEquals(paused, lifecycle.find(FIRST).orElseThrow());
    }

    @Test
    void simultaneousIdenticalTransitionsHaveOneCommitAndDeterministicDuplicateRejections() throws Exception {
        var initial = lifecycle.create(FIRST, HEAD);
        var request = command(initial, MARK_READY);
        race(initial, Collections.nCopies(8, request), DUPLICATE_TRANSITION);
    }

    @Test
    void simultaneousDistinctTransitionsOnOneVersionHaveOneCommit() throws Exception {
        var initial = reviewing(FIRST);
        List<ReviewTransition> commands = new ArrayList<>();
        for (int i = 0; i < 8; i++) commands.add(command(initial, i % 2 == 0 ? UPDATE_HEAD : BASELINE_CLEAN));
        race(initial, commands, STALE_VERSION);
    }

    void race(PullRequestReview initial, List<ReviewTransition> commands, ReviewConflict.Code rejection) throws Exception {
        var start = new CountDownLatch(1);
        long before = journalCount();
        try (var executor = Executors.newFixedThreadPool(commands.size())) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var request : commands) results.add(executor.submit(() -> {
                start.await();
                try {
                    newLifecycle().apply(initial.pr(), request);
                    return true;
                } catch (ReviewConflict conflict) {
                    assertEquals(rejection, conflict.code());
                    return false;
                }
            }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(before + 1, journalCount());
        assertEquals(initial.version() + 1, lifecycle.find(initial.pr()).orElseThrow().version());
    }

    @Test
    void scopesAndPrNumbersHaveIndependentStateAndIdempotencyKeys() {
        var first = lifecycle.create(FIRST, HEAD);
        var second = lifecycle.create(SECOND, HEAD);
        var thirdId = new PullRequestId(FIRST.scope(), 8);
        var third = lifecycle.create(thirdId, HEAD);
        var request = command(first, MARK_READY);
        lifecycle.apply(FIRST, request);
        lifecycle.apply(SECOND, request);
        lifecycle.apply(thirdId, request);
        assertEquals(3, journalCount());
        assertEquals(second.version() + 1, lifecycle.find(SECOND).orElseThrow().version());
        assertEquals(third.version() + 1, lifecycle.find(thirdId).orElseThrow().version());
        var missing = new PullRequestId(FIRST.scope(), 9);
        assertTrue(lifecycle.find(missing).isEmpty());
        assertCode(NOT_FOUND, () -> lifecycle.apply(missing, request));
    }

    @Test
    void revocationAndAuthorityFailureDenyReadsAndMutations() {
        var state = reviewing(FIRST);
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        assertThrows(AuthorizationDenied.class, () -> lifecycle.find(FIRST));
        assertThrows(AuthorizationDenied.class, () -> step(state, PAUSE));
        assertThrows(AuthorizationDenied.class, () -> lifecycle.create(new PullRequestId(FIRST.scope(), 8), HEAD));
        jdbc.update("UPDATE installation_repositories SET enabled = true WHERE installation_id = 101");
        assertEquals(state, lifecycle.find(FIRST).orElseThrow());
        var unavailable = new ReviewLifecycle(new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(dataSource)),
                new RepositoryAuthorization(Set.of(FIRST.scope()), scope -> { throw new IllegalStateException("fixture-secret"); }));
        assertThrows(AuthorizationUnavailable.class, () -> unavailable.apply(FIRST, command(state, PAUSE)));
        assertEquals(state.version(), journalCount());
        assertEquals(state, lifecycle.find(FIRST).orElseThrow());
        var foreign = new PullRequestId(new RepositoryScope(101, 2002), 7);
        assertThrows(AuthorizationDenied.class, () -> lifecycle.find(foreign));
    }

    @Test
    void commitFailureRollsBackStateAndTransitionKeySoRetryCanSucceed() {
        var initial = lifecycle.create(FIRST, HEAD);
        var request = command(initial, MARK_READY);
        jdbc.execute("""
                CREATE FUNCTION reject_review_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic commit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_review_commit AFTER INSERT ON pr_review_transitions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_review_commit()
                """);
        try {
            var failure = assertThrows(ReviewStateUnavailable.class, () -> lifecycle.apply(FIRST, request));
            assertEquals("REVIEW_STATE_UNAVAILABLE", failure.getMessage());
            assertTrue(failure.retryable());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(initial, newLifecycle().find(FIRST).orElseThrow());
            assertEquals(0, journalCount());
        } finally {
            jdbc.execute("DROP TRIGGER reject_review_commit ON pr_review_transitions");
            jdbc.execute("DROP FUNCTION reject_review_commit()");
        }
        assertEquals(WAITING_FOR_REVIEW, lifecycle.apply(FIRST, request).state());
        assertEquals(1, journalCount());
    }

    @Test
    void databaseRejectsInconsistentEvidenceAndPauseSnapshots() {
        lifecycle.create(FIRST, HEAD);
        for (String assignments : List.of("baseline_clean_sha = '" + NEXT + "'", "state = 'READY_FOR_HUMAN'",
                "state = 'PAUSED'", "resume_state = 'REVIEWING'", "deep_clean_sha = '" + HEAD + "'")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE pr_review_states SET " + assignments));
        }
        assertEquals(NEW, lifecycle.find(FIRST).orElseThrow().state());
    }

    @Test
    void v4UpgradePreservesExistingCyclesWithoutInventingCurrentHeadOrVerdicts() {
        String schema = "upgrade_review_fixture";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate();
            jdbc.update("INSERT INTO " + schema + ".installations (installation_id, account_id) VALUES (101, 1)");
            jdbc.update("INSERT INTO " + schema + ".installation_repositories (installation_id, repository_id) VALUES (101, 1001)");
            UUID cycle = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, status) VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'fixture', 'READY_FOR_HUMAN')", cycle, HEAD);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertEquals("READY_FOR_HUMAN", jdbc.queryForObject("SELECT status FROM " + schema + ".review_cycles WHERE id = ?", String.class, cycle));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".pr_review_states", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".pr_review_transitions", Integer.class));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    long journalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM pr_review_transitions", Long.class);
    }

    static void assertCode(ReviewConflict.Code code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ReviewConflict.class, operation).code());
    }
}
