package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.*;
import java.sql.Timestamp;
import java.time.Instant;
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

    AssessReviewOutcome outcomes() {
        var manager = new DataSourceTransactionManager(dataSource);
        return new AssessReviewOutcome(new JdbcReviewOutcomeStore(jdbc, new JdbcReviewStateStore(jdbc, manager)),
                new RepositoryAuthorization(Set.of(FIRST.scope(), SECOND.scope()), new JdbcInstallationAuthorization(jdbc)));
    }

    ReviewOutcome.Assessment outcome(PullRequestReview state, ReviewOutcome.Stage stage, ReviewOutcome.Conclusion conclusion) {
        var request = new ReviewRequest(state.pr(), state.headSha(), stage.name(), "codex", ReviewRequest.Trigger.PR_OPENED, 1, false);
        var ids = jdbc.query("SELECT id FROM review_requests WHERE request_key = ?", (rs, row) -> rs.getObject(1, UUID.class), request.key());
        UUID requestId = ids.isEmpty() ? UUID.randomUUID() : ids.getFirst();
        if (ids.isEmpty()) jdbc.update("""
                INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                    head_sha, stage, provider, actor_id, state, comment_id, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'codex', 1, 'COMMENT_CREATED', 1, '1970-01-01T00:00:00Z', '1970-01-01T00:00:00Z')
                """, requestId, request.key(), state.pr().scope().installationId(), state.pr().scope().repositoryId(),
                state.pr().number(), state.headSha(), stage.name());
        UUID cycleId = UUID.randomUUID();
        int iteration = jdbc.queryForObject("SELECT count(*) + 1 FROM review_cycles", Integer.class);
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                VALUES (?, ?, ?, ?, ?, ?, 'codex', ?)
                """, cycleId, state.pr().scope().installationId(), state.pr().scope().repositoryId(), state.pr().number(),
                state.headSha(), stage.name(), iteration);
        var run = new ReviewOutcome.Run(state.pr(), state.headSha(), "codex", stage, requestId, cycleId, "github", "fixture-codex");
        var source = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), state.headSha(), null, "0".repeat(64), java.time.Instant.EPOCH,
                "COMMENTED", null, null, null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        var snapshot = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), state.pr(), "github", state.headSha(),
                java.time.Instant.now(), List.of(source));
        new JdbcReviewEvidenceStore(jdbc, new DataSourceTransactionManager(dataSource)).ingest(snapshot, () -> {});
        return new ReviewOutcome.Assessment(run, state.version(),
                new ReviewOutcome.Completion(run, "R1", ReviewOutcome.Status.COMPLETED, conclusion), snapshot.id());
    }

    @Test
    void correlatedCleanStagesPersistButAmbiguousAndUnknownOutcomesCannotAdvance() {
        var state = reviewing(FIRST);
        var outcomes = outcomes();
        for (var conclusion : List.of(ReviewOutcome.Conclusion.UNKNOWN, ReviewOutcome.Conclusion.UNSTRUCTURED,
                ReviewOutcome.Conclusion.FINDINGS)) {
            var result = outcome(state, ReviewOutcome.Stage.BASELINE, conclusion);
            assertCode(GUARD_REJECTED, () -> outcomes.recordClean(result, UUID.randomUUID(), CLEAR));
            assertEquals(state, lifecycle.find(FIRST).orElseThrow());
        }
        var baseline = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(baseline).verdict());
        var commandId = UUID.randomUUID();
        state = outcomes.recordClean(baseline, commandId, CLEAR);
        assertEquals(DEEP_REVIEW, state.state());
        assertNull(state.deepCleanSha());
        var proof = jdbc.queryForMap("SELECT * FROM review_clean_evidence WHERE transition_id = ?", commandId);
        assertEquals("codex", proof.get("provider"));
        assertEquals("BASELINE", proof.get("stage"));
        assertEquals(HEAD, proof.get("head_sha"));
        assertEquals(baseline.snapshotId(), proof.get("snapshot_id"));
        assertCode(DUPLICATE_TRANSITION, () -> outcomes().recordClean(baseline, commandId, CLEAR));
        assertCode(TRANSITION_ID_REUSED, () -> outcomes.recordClean(new ReviewOutcome.Assessment(baseline.run(), baseline.expectedVersion(),
                new ReviewOutcome.Completion(baseline.run(), "R2", ReviewOutcome.Status.COMPLETED, ReviewOutcome.Conclusion.NO_FINDINGS),
                baseline.snapshotId()), commandId, CLEAR));
        var deep = outcome(state, ReviewOutcome.Stage.DEEP, ReviewOutcome.Conclusion.NO_FINDINGS);
        state = outcomes.recordClean(deep, UUID.randomUUID(), CLEAR);
        assertEquals(HEAD, newLifecycle().find(FIRST).orElseThrow().deepCleanSha());
        // Readiness still requires independent CI/eligibility gates.
        var pending = new Gates(true, false, false, true, false, Ci.UNKNOWN);
        var ready = new ReviewTransition(UUID.randomUUID(), state.version(), HEAD, MARK_READY_FOR_HUMAN, null, pending);
        assertCode(GUARD_REJECTED, () -> lifecycle.apply(FIRST, ready));
        assertEquals(READY_FOR_HUMAN, step(state, MARK_READY_FOR_HUMAN).state());
    }

    @Test
    void assessedCleanCannotSurviveForcePushBackToSameShaOrBeAppliedToAnotherStage() {
        var state = reviewing(FIRST);
        var outcomes = outcomes();
        var late = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var wrongStage = outcome(state, ReviewOutcome.Stage.DEEP, ReviewOutcome.Conclusion.NO_FINDINGS);
        assertCode(ILLEGAL_TRANSITION, () -> outcomes.recordClean(wrongStage, UUID.randomUUID(), CLEAR));
        state = step(state, UPDATE_HEAD);
        state = lifecycle.apply(FIRST, new ReviewTransition(UUID.randomUUID(), state.version(), NEXT, UPDATE_HEAD, HEAD, CLEAR));
        state = step(state, START_REVIEW);
        assertEquals(HEAD, state.headSha());
        assertCode(STALE_VERSION, () -> outcomes.recordClean(late, UUID.randomUUID(), CLEAR));
        assertCode(STALE_VERSION, () -> outcomes.assess(late));
        var refreshed = new ReviewOutcome.Assessment(late.run(), state.version(), late.completion(), late.snapshotId());
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(refreshed).verdict());
        var current = state;
        var foreignHead = new ReviewOutcome.Run(FIRST, NEXT, "codex", ReviewOutcome.Stage.BASELINE,
                UUID.randomUUID(), UUID.randomUUID(), "github", "fixture-codex");
        assertCode(STALE_HEAD, () -> outcomes.assess(new ReviewOutcome.Assessment(foreignHead, current.version(), null, null)));
        assertNull(lifecycle.find(FIRST).orElseThrow().baselineCleanSha());
    }

    @Test
    void historicalRoundSnapshotAtDifferentObservedHeadCannotEstablishCleanEvidence() {
        var state = reviewing(FIRST);
        var original = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(original).verdict());

        var run = original.run();
        var source = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), Instant.EPOCH, "COMMENTED", null, null,
                null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        Instant later = jdbc.queryForObject("SELECT captured_at FROM review_evidence_snapshots WHERE id = ?",
                Timestamp.class, original.snapshotId()).toInstant().plusSeconds(1);
        var snapshot = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github",
                HEAD, NEXT, later, List.of(source));
        new JdbcReviewEvidenceStore(jdbc, new DataSourceTransactionManager(dataSource)).ingest(snapshot, () -> {});

        var assessment = new ReviewOutcome.Assessment(run, state.version(), original.completion(), snapshot.id());
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(assessment).verdict());
        assertCode(GUARD_REJECTED, () -> outcomes.recordClean(assessment, UUID.randomUUID(), CLEAR));
        assertEquals(state, lifecycle.find(FIRST).orElseThrow());
    }

    @Test
    void confirmingSameHeadMakesPriorCompletionEvidenceStale() {
        var state = reviewing(FIRST);
        var original = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(original).verdict());
        state = step(step(state, CONFIRM_HEAD), START_REVIEW);
        var refreshed = new ReviewOutcome.Assessment(original.run(), state.version(), original.completion(), original.snapshotId());
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(refreshed).verdict());
        assertCode(GUARD_REJECTED, () -> outcomes.recordClean(refreshed, UUID.randomUUID(), CLEAR));
    }

    @Test
    void headUpdateWaitingBehindIngestionRejectsSnapshotFromBeforeTheUpdate() throws Exception {
        var initial = reviewing(FIRST);
        var original = outcome(initial, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var away = step(initial, UPDATE_HEAD);
        var back = new ReviewTransition(UUID.randomUUID(), away.version(), NEXT, UPDATE_HEAD, HEAD, CLEAR);
        var run = original.run();
        // A provider clock ahead of PostgreSQL must not hide the local write ordering.
        Instant providerTime = jdbc.queryForObject("SELECT clock_timestamp() + interval '1 minute'", Timestamp.class).toInstant();
        var review = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), providerTime, "COMMENTED", null, null,
                null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        var overlapping = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                providerTime.plusSeconds(1), List.of(review));
        var manager = new DataSourceTransactionManager(dataSource);
        var ingestion = new JdbcReviewEvidenceStore(jdbc, manager);
        var states = new JdbcReviewStateStore(jdbc, manager);
        var authorization = new JdbcInstallationAuthorization(jdbc);
        var ingestPid = new CompletableFuture<Integer>();
        var updatePid = new CompletableFuture<Integer>();
        PullRequestReview returned;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> stored;
            Future<PullRequestReview> updated;
            try (var lock = dataSource.getConnection()) {
                lock.setAutoCommit(false);
                try {
                    int cycleLockPid;
                    try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                        assertTrue(rows.next());
                        cycleLockPid = rows.getInt(1);
                    }
                    // Ingestion acquires the PR lock before it tries to lock the cycle row.
                    try (var statement = lock.prepareStatement("SELECT id FROM review_cycles WHERE id = ? FOR UPDATE")) {
                        statement.setObject(1, run.cycleId());
                        try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                    }
                    stored = workers.submit(() -> ingestion.ingest(overlapping, () -> {
                        assertTrue(authorization.isActiveAndEnabled(FIRST.scope()));
                        ingestPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    }));
                    int ingestBackend = ingestPid.get(2, TimeUnit.SECONDS);
                    awaitBlockedBy(ingestBackend, cycleLockPid);
                    updated = workers.submit(() -> states.apply(FIRST, back, () -> {
                        assertTrue(authorization.isActiveAndEnabled(FIRST.scope()));
                        updatePid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    }));
                    awaitBlockedBy(updatePid.get(2, TimeUnit.SECONDS), ingestBackend);
                } finally {
                    lock.rollback();
                }
            }
            assertTrue(stored.get(4, TimeUnit.SECONDS));
            returned = updated.get(4, TimeUnit.SECONDS);
        }
        var state = step(returned, START_REVIEW);
        var reassessed = new ReviewOutcome.Assessment(run, state.version(), original.completion(), overlapping.id());
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(reassessed).verdict());
        assertCode(GUARD_REJECTED, () -> outcomes.recordClean(reassessed, UUID.randomUUID(), CLEAR));
        assertNull(lifecycle.find(FIRST).orElseThrow().baselineCleanSha());
    }

    private void awaitBlockedBy(int waiter, int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, blocker, waiter))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("PostgreSQL backend " + waiter + " did not wait for backend " + blocker);
    }

    @Test
    void lastHeadChangeIsSelectedByVersionEvenWithOutOfOrderJournalTimes() {
        var initial = reviewing(FIRST);
        var away = step(initial, UPDATE_HEAD);
        var returned = lifecycle.apply(FIRST,
                new ReviewTransition(UUID.randomUUID(), away.version(), NEXT, UPDATE_HEAD, HEAD, CLEAR));
        var state = step(returned, START_REVIEW);
        // Historical transaction-start timestamps can be out of version order.
        jdbc.update("""
                UPDATE pr_review_transitions SET occurred_at = clock_timestamp() + interval '1 day'
                WHERE installation_id = 101 AND repository_id = 1001 AND pr_number = 7 AND resulting_version = ?
                """, away.version());
        var initialAssessment = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var run = initialAssessment.run();
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var review = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), now, "COMMENTED", null, null,
                null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        var snapshot = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                now.plusSeconds(1), List.of(review));
        new JdbcReviewEvidenceStore(jdbc, new DataSourceTransactionManager(dataSource)).ingest(snapshot, () -> {});
        var assessment = new ReviewOutcome.Assessment(run, state.version(), initialAssessment.completion(), snapshot.id());
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes().assess(assessment).verdict());
    }

    @Test
    void authorizationIsRecheckedBetweenOutcomeAssessmentAndCleanCommit() {
        var state = reviewing(FIRST);
        var clean = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        long before = journalCount();
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        var outcomes = outcomes();
        assertThrows(AuthorizationDenied.class, () -> outcomes.recordClean(clean, UUID.randomUUID(), CLEAR));
        assertThrows(AuthorizationDenied.class, () -> outcomes.assess(clean));
        assertEquals(before, journalCount());
    }

    @Test
    void durableCorrelationRejectsMissingWrongAndUnsentRequestsAndPreRequestReview() {
        var state = reviewing(FIRST);
        var assessment = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(assessment).verdict());
        var run = assessment.run();
        for (var invalidRun : List.of(
                new ReviewOutcome.Run(FIRST, HEAD, "codex", run.stage(), UUID.randomUUID(), run.cycleId(), "github", run.reviewer()),
                new ReviewOutcome.Run(FIRST, HEAD, "codex", run.stage(), run.requestId(), UUID.randomUUID(), "github", run.reviewer()),
                new ReviewOutcome.Run(FIRST, HEAD, "other", run.stage(), run.requestId(), run.cycleId(), "github", run.reviewer()),
                new ReviewOutcome.Run(FIRST, HEAD, "codex", ReviewOutcome.Stage.DEEP, run.requestId(), run.cycleId(), "github", run.reviewer()))) {
            var invalid = new ReviewOutcome.Assessment(invalidRun, state.version(),
                    new ReviewOutcome.Completion(invalidRun, "R1", ReviewOutcome.Status.COMPLETED, ReviewOutcome.Conclusion.NO_FINDINGS),
                    assessment.snapshotId());
            assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(invalid).verdict());
            assertCode(invalidRun.stage() == ReviewOutcome.Stage.DEEP ? ILLEGAL_TRANSITION : GUARD_REJECTED,
                    () -> outcomes.recordClean(invalid, UUID.randomUUID(), CLEAR));
        }
        jdbc.update("UPDATE review_requests SET state = 'ATTEMPTED', comment_id = NULL, completed_at = NULL WHERE id = ?", run.requestId());
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(assessment).verdict());
        jdbc.update("UPDATE review_requests SET state = 'COMMENT_CREATED', comment_id = 1, completed_at = clock_timestamp(), created_at = clock_timestamp() WHERE id = ?", run.requestId());
        // The independently reviewed source predates this request, despite matching the PR head.
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(assessment).verdict());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_clean_evidence", Integer.class));
        assertNull(lifecycle.find(FIRST).orElseThrow().baselineCleanSha());
    }

    @Test
    void newerSnapshotAndRetainedInlineFindingInvalidatePreviouslyCleanAssessment() {
        var state = reviewing(FIRST);
        var assessment = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(assessment).verdict());
        var run = assessment.run();
        var manager = new DataSourceTransactionManager(dataSource);
        var ingestion = new JdbcReviewEvidenceStore(jdbc, manager);
        var source = new ReviewEvidence.Source("C1", ReviewEvidence.Kind.REVIEW_COMMENT, "R1", "T1", null,
                run.reviewer(), HEAD, null, "0".repeat(64), java.time.Instant.EPOCH, null, true, true,
                "Example.java", null, 1, ReviewEvidence.Severity.HIGH);
        var changed = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                java.time.Instant.now().plusSeconds(1), List.of(source));
        ingestion.ingest(changed, () -> {});
        // The superseded reference cannot prove completion, but the known retained blocker still decides.
        assertEquals(ReviewOutcome.Verdict.ACTION_REQUIRED, outcomes.assess(assessment).verdict());
        assertCode(GUARD_REJECTED, () -> outcomes.recordClean(assessment, UUID.randomUUID(), CLEAR));
        // A subsequent read omits that root, but the retained finding must still block.
        var review = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), java.time.Instant.EPOCH, "COMMENTED", null, null,
                null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        var latest = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                changed.capturedAt().plusSeconds(1), List.of(review));
        ingestion.ingest(latest, () -> {});
        var rechecked = new ReviewOutcome.Assessment(run, state.version(), assessment.completion(), latest.id());
        assertEquals(ReviewOutcome.Verdict.ACTION_REQUIRED, outcomes.assess(rechecked).verdict());
        assertCode(GUARD_REJECTED, () -> outcomes.recordClean(rechecked, UUID.randomUUID(), CLEAR));
    }

    @Test
    void retainedInlineFindingsGateEveryPathUntilClosedForTheCurrentSnapshot() {
        var state = reviewing(FIRST);
        var assessment = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var run = assessment.run();
        var outcomes = outcomes();
        var ingestion = new JdbcReviewEvidenceStore(jdbc, new DataSourceTransactionManager(dataSource));
        var review = new ReviewEvidence.Source("R1", ReviewEvidence.Kind.REVIEW, "R1", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), java.time.Instant.EPOCH, "COMMENTED", null, null,
                null, null, null, ReviewEvidence.Severity.UNKNOWN, ReviewEvidence.Feedback.NONE);
        java.util.function.Function<String, ReviewEvidence.Source> inline = id -> new ReviewEvidence.Source(id,
                ReviewEvidence.Kind.REVIEW_COMMENT, "R1", "T-" + id, null, "human", HEAD, null, "0".repeat(64),
                java.time.Instant.EPOCH, null, false, false, "Example.java", 1, 1, ReviewEvidence.Severity.HIGH);
        var withInline = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                java.time.Instant.now().plusSeconds(1), List.of(review, inline.apply("C1")));
        ingestion.ingest(withInline, () -> {});
        var current = new ReviewOutcome.Assessment(run, state.version(), assessment.completion(), withInline.id());

        // The shared lifecycle guard sees retained inline findings, not only general feedback.
        var current0 = state;
        assertCode(GUARD_REJECTED, () -> lifecycle.apply(FIRST, command(current0, BASELINE_CLEAN)));
        // Missing correlation still reports the known blocker instead of UNKNOWN.
        assertEquals(ReviewOutcome.Verdict.ACTION_REQUIRED, outcomes.assess(
                new ReviewOutcome.Assessment(run, state.version(), assessment.completion(), null)).verdict());
        assertEquals(ReviewOutcome.Verdict.ACTION_REQUIRED, outcomes.assess(current).verdict());

        // A closed occurrence linked to this exact snapshot no longer blocks the visible comment.
        jdbc.update("""
                UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer', disposition_reason = 'not applicable'
                WHERE source_id = 'C1' AND evidence_snapshot_id = ?
                """, withInline.id());
        // Snapshots without a database ingestion time (pre-V9) cannot establish clean evidence.
        jdbc.update("UPDATE review_evidence_snapshots SET ingested_at = NULL WHERE id = ?", withInline.id());
        assertEquals(ReviewOutcome.Verdict.UNKNOWN, outcomes.assess(current).verdict());
        jdbc.update("UPDATE review_evidence_snapshots SET ingested_at = clock_timestamp() WHERE id = ?", withInline.id());
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(current).verdict());
        state = outcomes.recordClean(current, UUID.randomUUID(), CLEAR);
        assertEquals(HEAD, state.baselineCleanSha());

        // A new inline finding revokes the recorded clean stage during ingestion.
        var newer = new ReviewEvidence.Snapshot(UUID.randomUUID(), run.cycleId(), FIRST, "github", HEAD,
                withInline.capturedAt().plusSeconds(1), List.of(review, inline.apply("C1"), inline.apply("C2")));
        ingestion.ingest(newer, () -> {});
        var revoked = lifecycle.find(FIRST).orElseThrow();
        assertNull(revoked.baselineCleanSha());
        assertEquals(ACTION_REQUIRED, revoked.state());
    }

    @Test
    void cleanEvidenceAndTransitionRollBackTogetherOnDeferredCommitFailure() {
        var state = reviewing(FIRST);
        var assessment = outcome(state, ReviewOutcome.Stage.BASELINE, ReviewOutcome.Conclusion.NO_FINDINGS);
        var outcomes = outcomes();
        UUID commandId = UUID.randomUUID();
        long before = journalCount();
        jdbc.execute("""
                CREATE FUNCTION reject_clean_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic private provider diagnostics'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_clean_commit AFTER INSERT ON review_clean_evidence
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_clean_commit()
                """);
        try {
            var failure = assertThrows(ReviewStateUnavailable.class, () -> outcomes.recordClean(assessment, commandId, CLEAR));
            assertTrue(failure.retryable());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(state, lifecycle.find(FIRST).orElseThrow());
            assertEquals(before, journalCount());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_clean_evidence", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_clean_commit ON review_clean_evidence");
            jdbc.execute("DROP FUNCTION reject_clean_commit()");
        }
        assertEquals(DEEP_REVIEW, outcomes.recordClean(assessment, commandId, CLEAR).state());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM review_clean_evidence", Integer.class));
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
