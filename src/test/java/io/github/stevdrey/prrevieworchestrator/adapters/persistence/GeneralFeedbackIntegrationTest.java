package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GeneralFeedbackIntegrationTest {
    static final String HEAD = "a".repeat(40), NEXT = "b".repeat(40);
    static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    static final Instant TIME = Instant.parse("2026-11-16T11:00:00Z");
    static final Gates CLEAR = new Gates(true, false, false, true, false, Ci.PASSED);
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));
    JdbcTemplate jdbc;
    DataSource dataSource;
    RepositoryAuthorization authorization;
    IngestReviewEvidence ingestion;
    DisposeGeneralFeedback dispositions;
    ReviewLifecycle lifecycle;
    UUID cycle;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true)");
        authorization = new RepositoryAuthorization(Set.of(PR.scope()), new JdbcInstallationAuthorization(jdbc));
        var manager = new DataSourceTransactionManager(dataSource);
        ingestion = new IngestReviewEvidence(authorization, new JdbcReviewEvidenceStore(jdbc, manager));
        dispositions = new DisposeGeneralFeedback(authorization, new JdbcGeneralFeedbackStore(jdbc, manager));
        lifecycle = new ReviewLifecycle(new JdbcReviewStateStore(jdbc, manager), authorization);
        cycle = cycle(HEAD, 1);
        step(step(lifecycle.create(PR, HEAD), MARK_READY), START_REVIEW);
    }

    UUID cycle(String head, int iteration) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github', ?)
                """, id, head, iteration);
        return id;
    }

    Source source(Kind kind, Feedback feedback, String body, Instant updatedAt) {
        return new Source("general-1", kind, kind == Kind.REVIEW ? "general-1" : null, null, null, "reviewer",
                kind == Kind.REVIEW ? HEAD : null, null, Sha256.hex(body.getBytes(StandardCharsets.UTF_8)),
                updatedAt, kind == Kind.REVIEW ? "APPROVED" : null, null, null, null, null, null, Severity.UNKNOWN, feedback);
    }

    Snapshot observe(UUID round, String head, long seconds, Source... sources) {
        var snapshot = new Snapshot(UUID.randomUUID(), round, PR, "github", head, TIME.plusSeconds(seconds), List.of(sources));
        assertTrue(ingestion.ingest(snapshot));
        return snapshot;
    }

    PullRequestReview current() { return lifecycle.find(PR).orElseThrow(); }
    PullRequestReview step(PullRequestReview state, ReviewTransition.Action action) {
        return lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action,
                action == UPDATE_HEAD ? NEXT : null, CLEAR));
    }
    Disposition command(Snapshot snapshot, GeneralFeedback.Action action, String head) {
        UUID finding = jdbc.queryForObject("SELECT id FROM finding_identities WHERE source_id = 'general-1'", UUID.class);
        return new Disposition(UUID.randomUUID(), finding, snapshot.id(), head, current().version(), action, "fixture-author",
                "Explicit fixture decision", action == GeneralFeedback.Action.CLAIM_FIXED ? NEXT : null);
    }
    void blocked(ReviewTransition.Action action) {
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> step(current(), action)).code());
    }

    ReviewOutcome.Run baselineRun() {
        UUID requestId = UUID.randomUUID();
        var request = new ReviewRequest(PR, HEAD, "BASELINE", "github", ReviewRequest.Trigger.PR_OPENED, 1, false);
        jdbc.update("""
                INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                    head_sha, stage, provider, actor_id, state, comment_id, created_at, completed_at, iteration)
                VALUES (?, ?, 101, 1001, 7, ?, 'BASELINE', 'github', 1, 'COMMENT_CREATED', 1, '1970-01-01', '1970-01-01', 1)
                """, requestId, request.key(), HEAD);
        return new ReviewOutcome.Run(PR, HEAD, "github", ReviewOutcome.Stage.BASELINE,
                requestId, cycle, "github", "reviewer");
    }

    AssessReviewOutcome outcomes() {
        var manager = new DataSourceTransactionManager(dataSource);
        return new AssessReviewOutcome(new JdbcReviewOutcomeStore(jdbc, new JdbcReviewStateStore(jdbc, manager)), authorization);
    }

    ReviewOutcome.Assessment assessment(ReviewOutcome.Run run, Snapshot snapshot, String reviewId, ReviewOutcome.Conclusion conclusion) {
        return new ReviewOutcome.Assessment(run, current().version(),
                new ReviewOutcome.Completion(run, reviewId, ReviewOutcome.Status.COMPLETED, conclusion), snapshot.id());
    }

    @Test
    void feedbackArrivingAfterCleanAssessmentStillBlocksItsCommit() {
        var run = baselineRun();
        var evidence = observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NONE, "", TIME));
        var outcomes = outcomes();
        var assessment = assessment(run, evidence, "general-1", ReviewOutcome.Conclusion.NO_FINDINGS);
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(assessment).verdict());
        observe(cycle, HEAD, 1, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "LGTM, but verify the failure path", TIME.plusSeconds(1)));
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> outcomes.recordClean(assessment, UUID.randomUUID(), CLEAR)).code());
        assertNull(current().baselineCleanSha());
    }

    @Test
    void cleanOutcomeHonorsDismissalOfVisibleFeedbackAndBlocksAnEditUntilDismissedAgain() {
        var run = baselineRun();
        var outcomes = outcomes();
        var initial = source(Kind.ISSUE_COMMENT, Feedback.NEEDS_HUMAN, "Question for the maintainer", TIME);
        var first = observe(cycle, HEAD, 0, initial);
        assertTrue(dispositions.dispose(PR, command(first, GeneralFeedback.Action.DISMISS, HEAD)));

        var review = new Source("completed-review", Kind.REVIEW, "completed-review", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), TIME.plusSeconds(1), "COMMENTED", null, null,
                null, null, null, Severity.UNKNOWN, Feedback.NONE);
        var reread = observe(cycle, HEAD, 1, review, initial);
        var clean = assessment(run, reread, review.id(), ReviewOutcome.Conclusion.NO_FINDINGS);
        assertEquals(ReviewOutcome.Verdict.CLEAN_FOR_SHA, outcomes.assess(clean).verdict());

        var edit = source(Kind.ISSUE_COMMENT, Feedback.NEEDS_HUMAN, "New concern for the maintainer", TIME.plusSeconds(2));
        var edited = observe(cycle, HEAD, 2, review, edit);
        var current = assessment(run, edited, review.id(), ReviewOutcome.Conclusion.NO_FINDINGS);
        assertEquals(ReviewOutcome.Verdict.NEEDS_HUMAN, outcomes.assess(current).verdict());
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> outcomes.recordClean(current, UUID.randomUUID(), CLEAR)).code());
        assertTrue(dispositions.dispose(PR, command(edited, GeneralFeedback.Action.DISMISS, HEAD)));
        assertEquals(HEAD, outcomes.recordClean(current, UUID.randomUUID(), CLEAR).baselineCleanSha());
    }

    @ParameterizedTest
    @CsvSource({"CHANGES_REQUESTED,NO_FINDINGS", "COMMENTED,FINDINGS"})
    void correlatedActionableCompletionTakesPrecedenceOverAmbiguousReviewBody(
            String reviewState, ReviewOutcome.Conclusion conclusion) {
        var run = baselineRun();
        var review = new Source("completed-review", Kind.REVIEW, "completed-review", null, null,
                run.reviewer(), HEAD, null, "0".repeat(64), TIME, reviewState, null, null,
                null, null, null, Severity.UNKNOWN, Feedback.NEEDS_HUMAN);
        var evidence = observe(cycle, HEAD, 0, review);
        var outcomes = outcomes();
        assertEquals(ReviewOutcome.Verdict.ACTION_REQUIRED, outcomes.assess(assessment(run, evidence, review.id(), conclusion)).verdict());
        assertEquals(ReviewOutcome.Verdict.NEEDS_HUMAN, outcomes.assess(
                new ReviewOutcome.Assessment(run, current().version(), null, evidence.id())).verdict());
    }

    @Test
    void actionableAndAmbiguousGeneralOnlyFeedbackBlockDespiteCallerClearGates() {
        for (Kind kind : List.of(Kind.REVIEW, Kind.ISSUE_COMMENT)) {
            for (Feedback feedback : List.of(Feedback.ACTIONABLE, Feedback.NEEDS_HUMAN)) {
                jdbc.execute("TRUNCATE review_evidence_snapshots, finding_identities CASCADE");
                observe(cycle, HEAD, 0, source(kind, feedback, "Unstructured feedback", TIME));
                blocked(BASELINE_CLEAN);
                var row = jdbc.queryForMap("SELECT * FROM findings");
                assertNull(row.get("thread_id"));
                assertNull(row.get("thread_resolved"));
                assertEquals("OPEN", row.get("status"));
            }
        }
    }

    @Test
    void acknowledgeAndFixClaimAreAuditedButOnlyExplicitDismissalUnblocks() {
        var snapshot = observe(cycle, HEAD, 0, source(Kind.ISSUE_COMMENT, Feedback.NEEDS_HUMAN, "Please check", TIME));
        var acknowledge = command(snapshot, GeneralFeedback.Action.ACKNOWLEDGE, HEAD);
        assertTrue(dispositions.dispose(PR, acknowledge));
        assertFalse(dispositions.dispose(PR, acknowledge));
        blocked(BASELINE_CLEAN);
        var claim = command(snapshot, GeneralFeedback.Action.CLAIM_FIXED, HEAD);
        assertTrue(dispositions.dispose(PR, claim));
        blocked(BASELINE_CLEAN);
        assertEquals(NEXT, jdbc.queryForObject("SELECT evidence_sha FROM general_feedback_dispositions WHERE id = ?", String.class, claim.id()));
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertNull(jdbc.queryForObject("SELECT verified_at FROM findings", Object.class));
        assertTrue(dispositions.dispose(PR, command(snapshot, GeneralFeedback.Action.DISMISS, HEAD)));
        var restarted = new DisposeGeneralFeedback(authorization,
                new JdbcGeneralFeedbackStore(jdbc, new DataSourceTransactionManager(dataSource)));
        assertFalse(restarted.dispose(PR, acknowledge)); // An old retry cannot undo the newer dismissal.
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM general_feedback_dispositions", Integer.class));
        assertEquals(PullRequestReview.State.DEEP_REVIEW, step(current(), BASELINE_CLEAN).state());
    }

    @Test
    void editsBlankingAndAbsenceNeverProveAFixAndOldCapturesCannotUndoNewFeedback() {
        var source = source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Question", TIME);
        var initial = observe(cycle, HEAD, 0, source);
        dispositions.dispose(PR, command(initial, GeneralFeedback.Action.DISMISS, HEAD));
        var reread = observe(cycle, HEAD, 1, source);
        assertFalse(JdbcGeneralFeedbackStore.blocking(jdbc, PR, HEAD));
        assertThrows(Conflict.class, () -> dispositions.dispose(PR, command(initial, GeneralFeedback.Action.DISMISS, HEAD)));
        var edited = observe(cycle, HEAD, 3, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "New concern", TIME.plusSeconds(2)));
        blocked(BASELINE_CLEAN);
        observe(cycle, HEAD, 2, source); // arrives after the edit, but its read started earlier
        blocked(BASELINE_CLEAN);
        dispositions.dispose(PR, command(edited, GeneralFeedback.Action.DISMISS, HEAD));
        observe(cycle, HEAD, 4, source(Kind.REVIEW, Feedback.NONE, "", TIME.plusSeconds(4)));
        blocked(BASELINE_CLEAN);
        observe(cycle, HEAD, 5); // deletion/absence is not disposition
        blocked(BASELINE_CLEAN);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM finding_identities", Integer.class));
        assertNotEquals(initial.id(), reread.id());
    }

    @ParameterizedTest
    @CsvSource({"REVIEW,false,false", "REVIEW,false,true", "REVIEW,true,false", "REVIEW,true,true",
            "ISSUE_COMMENT,false,false", "ISSUE_COMMENT,false,true", "ISSUE_COMMENT,true,false", "ISSUE_COMMENT,true,true"})
    void latestBlankRevisionGovernsRegardlessOfArrivalOrder(Kind kind, boolean crossRound, boolean reverse) {
        UUID newerCycle = crossRound ? cycle(HEAD, 2) : cycle;
        var older = new Snapshot(UUID.randomUUID(), cycle, PR, "github", HEAD, TIME,
                List.of(source(kind, Feedback.NEEDS_HUMAN, "Concern", TIME)));
        var newer = new Snapshot(UUID.randomUUID(), newerCycle, PR, "github", HEAD, TIME.plusSeconds(1),
                List.of(source(kind, Feedback.NONE, "", TIME.plusSeconds(1))));
        if (reverse) {
            assertTrue(ingestion.ingest(newer));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM finding_identities", Integer.class));
            assertTrue(ingestion.ingest(older));
        } else {
            assertTrue(ingestion.ingest(older));
            assertTrue(ingestion.ingest(newer));
        }
        assertFalse(ingestion.ingest(older));
        assertFalse(ingestion.ingest(newer));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM finding_identities", Integer.class));
        assertEquals(crossRound ? 2 : 1, jdbc.queryForObject("SELECT count(*) FROM findings", Integer.class));
        assertEquals(newer.id(), jdbc.queryForObject("SELECT evidence_snapshot_id FROM findings WHERE review_cycle_id = ?",
                UUID.class, newerCycle));
        assertThrows(Conflict.class, () -> dispositions.dispose(PR, command(older, GeneralFeedback.Action.DISMISS, HEAD)));
        blocked(BASELINE_CLEAN);
        assertTrue(dispositions.dispose(PR, command(newer, GeneralFeedback.Action.DISMISS, HEAD)));
        assertEquals(PullRequestReview.State.DEEP_REVIEW, step(current(), BASELINE_CLEAN).state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deterministicDispositionErrorsStayNonRetryableAndRollBack(boolean invalidActor) {
        var snapshot = observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME));
        var valid = command(snapshot, GeneralFeedback.Action.DISMISS, HEAD);
        var invalid = new Disposition(valid.id(), valid.findingId(), valid.expectedSnapshotId(), HEAD, valid.expectedVersion(),
                valid.action(), invalidActor ? "actor\0" : valid.actor(), invalidActor ? valid.reason() : "reason\0", null);
        var failure = assertThrows(Unavailable.class, () -> dispositions.dispose(PR, invalid));
        assertFalse(failure.retryable());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals("Review evidence unavailable", failure.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_feedback_dispositions", Integer.class));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
        blocked(BASELINE_CLEAN);
        assertTrue(dispositions.dispose(PR, valid)); // The rejected command did not consume its idempotency key.
    }

    @Test
    void headChangesInvalidateDispositionAndStableIdentitySurvivesRounds() {
        var source = source(Kind.ISSUE_COMMENT, Feedback.ACTIONABLE, "Fix this", TIME);
        var initial = observe(cycle, HEAD, 0, source);
        dispositions.dispose(PR, command(initial, GeneralFeedback.Action.DISMISS, HEAD));
        step(current(), UPDATE_HEAD);
        step(current(), START_REVIEW);
        blocked(BASELINE_CLEAN); // before any source is re-observed at the new head
        assertThrows(Conflict.class, () -> dispositions.dispose(PR, command(initial, GeneralFeedback.Action.DISMISS, HEAD)));
        var next = observe(cycle(NEXT, 2), NEXT, 1, source);
        blocked(BASELINE_CLEAN);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM finding_identities", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM findings", Integer.class));
        dispositions.dispose(PR, command(next, GeneralFeedback.Action.DISMISS, NEXT));
        step(current(), BASELINE_CLEAN);
    }

    @Test
    void returningToAnEarlierHeadNeverRevivesItsPriorDismissal() {
        var snapshot = observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME));
        var stale = command(snapshot, GeneralFeedback.Action.DISMISS, HEAD);
        dispositions.dispose(PR, stale);
        step(current(), UPDATE_HEAD);
        var state = current();
        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), state.version(), NEXT, UPDATE_HEAD, HEAD, CLEAR));
        step(current(), START_REVIEW);
        blocked(BASELINE_CLEAN);
        var replayWithNewId = new Disposition(UUID.randomUUID(), stale.findingId(), snapshot.id(), HEAD,
                stale.expectedVersion(), stale.action(), stale.actor(), stale.reason(), null);
        assertThrows(Conflict.class, () -> dispositions.dispose(PR, replayWithNewId));
        dispositions.dispose(PR, command(snapshot, GeneralFeedback.Action.DISMISS, HEAD));
        step(current(), BASELINE_CLEAN);
    }

    @Test
    void newFeedbackRevokesCleanEvidenceIncludingPausedReadinessAndBlocksDeepGate() {
        step(current(), BASELINE_CLEAN);
        observe(cycle, HEAD, 0, source(Kind.ISSUE_COMMENT, Feedback.NEEDS_HUMAN, "Concern", TIME));
        assertEquals(PullRequestReview.State.ACTION_REQUIRED, current().state());
        assertEquals(current().version(), jdbc.queryForObject("""
                SELECT state_version FROM author_notifications WHERE state = 'ACTION_REQUIRED' AND head_sha = ?
                """, Long.class, HEAD));
        assertNull(current().baselineCleanSha());
        // A separate PR can already have both clean stage verdicts when feedback is first observed.
        jdbc.execute("TRUNCATE review_evidence_snapshots, finding_identities CASCADE");
        jdbc.update("UPDATE pr_review_states SET state = 'DEEP_REVIEW', baseline_clean_sha = ?, deep_clean_sha = ?", HEAD, HEAD);
        step(current(), PAUSE);
        long version = current().version();
        observe(cycle, HEAD, 1, source(Kind.REVIEW, Feedback.ACTIONABLE, "Late concern", TIME));
        assertEquals(PullRequestReview.State.PAUSED, current().state());
        assertEquals(PullRequestReview.State.ACTION_REQUIRED, current().resumeState());
        assertNull(current().baselineCleanSha());
        assertNull(current().deepCleanSha());
        assertEquals(version + 1, current().version());
        step(current(), RESUME);
        assertEquals(PullRequestReview.State.ACTION_REQUIRED, current().state());
    }

    @Test
    void bothDeepTransitionsCheckPersistedFeedbackEvenIfCallerSuppliesClearGates() {
        observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME));
        // Simulate an already-clean legacy projection to exercise the last defensive guard independently.
        jdbc.update("UPDATE pr_review_states SET state = 'DEEP_REVIEW', baseline_clean_sha = ?", HEAD);
        blocked(DEEP_CLEAN);
        jdbc.update("UPDATE pr_review_states SET deep_clean_sha = ?", HEAD);
        blocked(MARK_READY_FOR_HUMAN);
    }

    @Test
    void v6UpgradeEscalatesExistingReviewBodiesRevokesReadinessAndRetainsExactRetryCompatibility() {
        String schema = "general_upgrade_fixture";
        String url = POSTGRES.getJdbcUrl();
        var scopedDataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        var scoped = new JdbcTemplate(scopedDataSource);
        UUID observation = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID oldCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
            scoped.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
            scoped.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true)");
            scoped.update("""
                    INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                    VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github')
                    """, oldCycle, HEAD);
            scoped.update("""
                    INSERT INTO pr_review_states (installation_id, repository_id, pr_number, head_sha, state,
                        baseline_clean_sha, deep_clean_sha) VALUES (101, 1001, 7, ?, 'READY_FOR_HUMAN', ?, ?)
                    """, HEAD, HEAD, HEAD);
            scoped.update("""
                    INSERT INTO review_evidence_snapshots (id, review_cycle_id, installation_id, repository_id,
                        pr_number, head_sha, provider, captured_at, fingerprint)
                    VALUES (?, ?, 101, 1001, 7, ?, 'github', ?, ?)
                    """, observation, oldCycle, HEAD, java.sql.Timestamp.from(TIME),
                    "e45cbd342fcb82ad2a0766efaf7341adc2a7e2cdf500fb88b65b3bbb32986771");
            var oldSource = source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "legacy body", TIME);
            scoped.update("""
                    INSERT INTO review_evidence_sources (snapshot_id, source_id, source_kind, review_id, author_login,
                        reviewed_sha, body_sha256, source_updated_at, review_state, severity)
                    VALUES (?, 'general-1', 'REVIEW', 'general-1', 'reviewer', ?, ?, ?, 'APPROVED', 'UNKNOWN')
                    """, observation, HEAD, oldSource.bodySha256(), java.sql.Timestamp.from(TIME));
            UUID legacyCycle = UUID.randomUUID();
            scoped.update("""
                    INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                    VALUES (?, 101, 1001, 8, ?, 'BASELINE', 'github')
                    """, legacyCycle, HEAD);
            scoped.update("""
                    INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha,
                        source_provider, source_kind, source_id) VALUES (?, ?, 101, 1001, ?, 'github', 'ISSUE_COMMENT', 'legacy-issue')
                    """, UUID.randomUUID(), legacyCycle, HEAD);
            scoped.update("""
                    INSERT INTO pr_review_states (installation_id, repository_id, pr_number, head_sha, state,
                        baseline_clean_sha, deep_clean_sha) VALUES (101, 1001, 8, ?, 'READY_FOR_HUMAN', ?, ?)
                    """, HEAD, HEAD, HEAD);
            var migration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            assertEquals(10, migration.migrate().migrationsExecuted);
            assertEquals("NEEDS_HUMAN", scoped.queryForObject("SELECT feedback FROM review_evidence_sources", String.class));
            assertEquals(1, scoped.queryForObject("SELECT count(*) FROM findings WHERE source_kind = 'REVIEW' AND thread_id IS NULL", Integer.class));
            assertEquals("ACTION_REQUIRED", scoped.queryForObject("SELECT state FROM pr_review_states WHERE pr_number = 7", String.class));
            assertEquals(1L, scoped.queryForObject("SELECT version FROM pr_review_states WHERE pr_number = 7", Long.class));
            assertEquals("BLOCKING_FINDINGS", scoped.queryForObject("SELECT action FROM pr_review_transitions WHERE pr_number = 7", String.class));
            var upgraded = new JdbcReviewEvidenceStore(scoped, new DataSourceTransactionManager(scopedDataSource));
            assertFalse(upgraded.ingest(new Snapshot(observation, oldCycle, PR, "github", HEAD, TIME, List.of(oldSource)), () -> {}));
            assertTrue(JdbcGeneralFeedbackStore.blocking(scoped, PR, HEAD));
            assertEquals("ACTION_REQUIRED", scoped.queryForObject("SELECT state FROM pr_review_states WHERE pr_number = 8", String.class));
            assertTrue(JdbcGeneralFeedbackStore.blocking(scoped, new PullRequestId(PR.scope(), 8), HEAD));
            assertTrue(migration.validateWithResult().validationSuccessful);
            assertEquals(0, migration.migrate().migrationsExecuted);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS general_upgrade_fixture CASCADE"); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void v6CrossRoundBlankUpgradeRequiresLatestRevisionDisposition(boolean includeOtherProvider) {
        String schema = "cross_round_upgrade_fixture";
        String url = POSTGRES.getJdbcUrl();
        var scopedDataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        var scoped = new JdbcTemplate(scopedDataSource);
        UUID oldCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID newCycle = UUID.fromString("00000000-0000-0000-0000-000000000004");
        var older = new Snapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), oldCycle, PR,
                "github", HEAD, TIME, List.of(source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "legacy body", TIME)));
        var newer = new Snapshot(UUID.fromString("00000000-0000-0000-0000-000000000003"), newCycle, PR,
                "github", HEAD, TIME.plusSeconds(1), List.of(source(Kind.REVIEW, Feedback.NONE, "", TIME.plusSeconds(1))));
        var other = new Snapshot(UUID.fromString("00000000-0000-0000-0000-000000000005"), newCycle, PR,
                "other", HEAD, TIME.plusSeconds(2), List.of(source(Kind.REVIEW, Feedback.NONE, "", TIME.plusSeconds(2))));
        var observations = new ArrayList<>(List.of(older, newer));
        if (includeOtherProvider) observations.add(other);
        // Frozen V6 fingerprints: exercise old persistence retries without using the current private serializer.
        var fingerprints = Map.of(
                older.id(), "e45cbd342fcb82ad2a0766efaf7341adc2a7e2cdf500fb88b65b3bbb32986771",
                newer.id(), "3c84aa0f05350dcb39084d50d42256a66330aad138d7c8b0dcbb6de1fc422503",
                other.id(), "b43da5a98fc1a33f149ad2332b6514ab253135cb255874e9f25c985879e71568");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
            scoped.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
            scoped.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true)");
            for (var round : List.of(oldCycle, newCycle)) {
                scoped.update("""
                        INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                        VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github', ?)
                        """, round, HEAD, round.equals(oldCycle) ? 1 : 2);
            }
            for (var observation : observations) {
                scoped.update("""
                        INSERT INTO review_evidence_snapshots (id, review_cycle_id, installation_id, repository_id,
                            pr_number, head_sha, provider, captured_at, fingerprint)
                        VALUES (?, ?, 101, 1001, 7, ?, ?, ?, ?)
                        """, observation.id(), observation.cycleId(), HEAD, observation.provider(),
                        java.sql.Timestamp.from(observation.capturedAt()), fingerprints.get(observation.id()));
                var revision = observation.sources().getFirst();
                scoped.update("""
                        INSERT INTO review_evidence_sources (snapshot_id, source_id, source_kind, review_id, author_login,
                            reviewed_sha, body_sha256, source_updated_at, review_state, severity)
                        VALUES (?, 'general-1', 'REVIEW', 'general-1', 'reviewer', ?, ?, ?, 'APPROVED', 'UNKNOWN')
                        """, observation.id(), HEAD, revision.bodySha256(), java.sql.Timestamp.from(revision.updatedAt()));
            }
            var migration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            assertEquals(10, migration.migrate().migrationsExecuted);
            assertEquals(observations.size(), scoped.queryForObject("SELECT count(*) FROM review_evidence_sources", Integer.class));
            assertEquals(1, scoped.queryForObject("SELECT count(*) FROM finding_identities", Integer.class));
            assertEquals(2, scoped.queryForObject("SELECT count(*) FROM findings", Integer.class));
            assertEquals(newer.id(), scoped.queryForObject("SELECT evidence_snapshot_id FROM findings WHERE review_cycle_id = ?",
                    UUID.class, newCycle));
            var manager = new DataSourceTransactionManager(scopedDataSource);
            var auth = new RepositoryAuthorization(Set.of(PR.scope()), new JdbcInstallationAuthorization(scoped));
            var upgraded = new IngestReviewEvidence(auth, new JdbcReviewEvidenceStore(scoped, manager));
            for (var observation : observations) assertFalse(upgraded.ingest(observation));
            assertEquals(2, scoped.queryForObject("SELECT count(*) FROM findings", Integer.class));
            var upgradedLifecycle = new ReviewLifecycle(new JdbcReviewStateStore(scoped, manager), auth);
            var state = upgradedLifecycle.create(PR, HEAD);
            for (var action : List.of(MARK_READY, START_REVIEW)) {
                state = upgradedLifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), state.version(), HEAD, action, null, CLEAR));
            }
            var reviewing = state;
            UUID finding = scoped.queryForObject("SELECT id FROM finding_identities", UUID.class);
            var upgradedDispositions = new DisposeGeneralFeedback(auth, new JdbcGeneralFeedbackStore(scoped, manager));
            assertThrows(Conflict.class, () -> upgradedDispositions.dispose(PR, new Disposition(UUID.randomUUID(), finding,
                    older.id(), HEAD, reviewing.version(), GeneralFeedback.Action.DISMISS, "fixture-author", "Stale decision", null)));
            assertTrue(JdbcGeneralFeedbackStore.blocking(scoped, PR, HEAD));
            assertEquals(ReviewConflict.Code.GUARD_REJECTED, assertThrows(ReviewConflict.class,
                    () -> upgradedLifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), reviewing.version(), HEAD,
                            BASELINE_CLEAN, null, CLEAR))).code());
            assertTrue(upgradedDispositions.dispose(PR, new Disposition(UUID.randomUUID(), finding,
                    newer.id(), HEAD, reviewing.version(), GeneralFeedback.Action.DISMISS, "fixture-author", "Current decision", null)));
            assertFalse(JdbcGeneralFeedbackStore.blocking(scoped, PR, HEAD));
            state = upgradedLifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), reviewing.version(), HEAD,
                    BASELINE_CLEAN, null, CLEAR));
            assertEquals(PullRequestReview.State.DEEP_REVIEW, state.state());
            assertEquals(HEAD, state.baselineCleanSha());
            assertTrue(migration.validateWithResult().validationSuccessful);
            assertEquals(0, migration.migrate().migrationsExecuted);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS cross_round_upgrade_fixture CASCADE"); }
    }

    @Test
    void concurrentExactRetriesAndConflictingIdsAreSafeAndAuthorizationIsRechecked() throws Exception {
        var snapshot = observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME));
        var command = command(snapshot, GeneralFeedback.Action.DISMISS, HEAD);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> dispositions.dispose(PR, command)));
            int committed = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) committed++;
            assertEquals(1, committed);
        }
        var conflict = new Disposition(command.id(), command.findingId(), command.expectedSnapshotId(), HEAD, command.expectedVersion(),
                GeneralFeedback.Action.ACKNOWLEDGE, command.actor(), command.reason(), null);
        assertThrows(Conflict.class, () -> dispositions.dispose(PR, conflict));
        var otherPr = new PullRequestId(PR.scope(), 8);
        assertThrows(Conflict.class, () -> dispositions.dispose(otherPr, command));
        jdbc.update("UPDATE installation_repositories SET enabled = false");
        assertThrows(AuthorizationDenied.class, () -> dispositions.dispose(PR, command));
    }

    @Test
    void concurrentIngestionAndCleanTransitionCannotLeaveAStaleCleanStage() throws Exception {
        var state = current();
        var snapshot = new Snapshot(UUID.randomUUID(), cycle, PR, "github", HEAD, TIME,
                List.of(source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME)));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ingest = pool.submit(() -> { barrier.await(); return ingestion.ingest(snapshot); });
            var clean = pool.submit(() -> {
                barrier.await();
                try { step(state, BASELINE_CLEAN); }
                catch (ReviewConflict rejected) { assertEquals(ReviewConflict.Code.GUARD_REJECTED, rejected.code()); }
                return true;
            });
            assertTrue(ingest.get(10, TimeUnit.SECONDS));
            assertTrue(clean.get(10, TimeUnit.SECONDS));
        }
        assertNull(current().baselineCleanSha());
        assertNotEquals(PullRequestReview.State.DEEP_REVIEW, current().state());
        assertTrue(JdbcGeneralFeedbackStore.blocking(jdbc, PR, HEAD));
    }

    @Test
    void commitFailureRollsBackDispositionAuditProjectionAndReadiness() {
        var snapshot = observe(cycle, HEAD, 0, source(Kind.REVIEW, Feedback.NEEDS_HUMAN, "Concern", TIME));
        jdbc.execute("""
                CREATE FUNCTION reject_disposition_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private-disposition-diagnostic'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_disposition_fixture AFTER INSERT ON general_feedback_dispositions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_disposition_fixture()
                """);
        try {
            var failure = assertThrows(Unavailable.class, () -> dispositions.dispose(PR,
                    command(snapshot, GeneralFeedback.Action.DISMISS, HEAD)));
            assertTrue(failure.retryable());
            assertNull(failure.getCause());
            assertEquals("Review evidence unavailable", failure.getMessage());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_feedback_dispositions", Integer.class));
            assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
            blocked(BASELINE_CLEAN);
        } finally {
            jdbc.execute("DROP TRIGGER reject_disposition_fixture ON general_feedback_dispositions");
            jdbc.execute("DROP FUNCTION reject_disposition_fixture()");
        }
    }
}
