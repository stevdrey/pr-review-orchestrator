package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Status.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubReviewNormalizer;
import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.*;
import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback.Disposition;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore.Receipt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FixCorrelationIntegrationTest {
    private static final String BASE = "a".repeat(40), FIRST = "b".repeat(40), LAST = "c".repeat(40);
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    private static final Instant TIME = Instant.parse("2026-11-18T12:00:00Z");
    private static final Gates READY = new Gates(true, false, false, true, false, Ci.PASSED);
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private RepositoryAuthorization authorization;
    private ReviewLifecycle lifecycle;
    private IngestReviewEvidence ingestion;
    private DisposeGeneralFeedback dispositions;
    private CorrelateFixes correlation;
    private UUID cycle;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        configure(dataSource);
        jdbc.execute("TRUNCATE installations CASCADE");
        createReviewingPr();
    }

    private void configure(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        authorization = new RepositoryAuthorization(Set.of(PR.scope()), new JdbcInstallationAuthorization(jdbc));
        lifecycle = new ReviewLifecycle(new JdbcReviewStateStore(jdbc, transactions), authorization);
        ingestion = new IngestReviewEvidence(authorization, new JdbcReviewEvidenceStore(jdbc, transactions));
        dispositions = new DisposeGeneralFeedback(authorization, new JdbcGeneralFeedbackStore(jdbc, transactions));
        correlation = newCorrelator();
    }

    private void createReviewingPr() {
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true)");
        cycle = createCycle(BASE, 1);
        lifecycle.create(PR, BASE);
        step(MARK_READY, READY);
        step(START_REVIEW, READY);
    }

    @Test
    void closePushesCoalesceAtTheLatestHeadAcrossWorkersAndStoreRecreation() throws Exception {
        var normalizer = new GitHubReviewNormalizer();
        String fixture = inlineReviewFixture();
        var initial = normalizer.normalize(UUID.randomUUID(), cycle, PR, BASE, TIME,
                fixture.getBytes(StandardCharsets.UTF_8));
        waitingForFix(initial);
        var first = event("pull_request", "synchronize", FIRST);
        var expected = current();
        assertTrue(correlation.observeSynchronize(first, expected, head(FIRST)));
        assertTrue(correlation.observeSynchronize(first, expected, head(FIRST)), "a replay still reports the head change");
        var beforeLast = assess(FIRST, null);
        assertEquals(WAITING_FOR_PUSHES, beforeLast.status());
        assertEquals(0, candidateCount());
        byte[] response = fixture.replace("\"headRefOid\": \"" + BASE, "\"headRefOid\": \"" + FIRST)
                .replace("\"isResolved\": false", "\"isResolved\": true").getBytes(StandardCharsets.UTF_8);
        var threadEvent = event("pull_request_review_thread", "resolved", FIRST);
        var snapshotId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalizeForRound(
                snapshotId, cycle, PR, BASE, LAST, TIME.plusSeconds(1), response, Map.of(), Set.of()));
        var resolved = normalizer.normalizeForRound(
                snapshotId, cycle, PR, BASE, FIRST, TIME.plusSeconds(1), response, Map.of(), Set.of());
        assertTrue(correlation.observeThread(threadEvent, resolved));
        assertFalse(correlation.observeThread(threadEvent, resolved));
        assertEquals(Map.of("head_sha", BASE, "observed_head_sha", FIRST), jdbc.queryForMap("""
                SELECT head_sha, observed_head_sha FROM review_evidence_snapshots WHERE id = ?
                """, snapshotId));
        assertEquals(Map.of("reviewed_sha", BASE, "original_sha", LAST, "thread_resolved", true),
                jdbc.queryForMap("""
                    SELECT reviewed_sha, original_sha, thread_resolved FROM review_evidence_sources
                    WHERE snapshot_id = ? AND source_id = 'C1'
                    """, snapshotId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
        var conflicting = new Snapshot(snapshotId, cycle, PR, "github", BASE, LAST, resolved.capturedAt(), resolved.sources());
        assertThrows(ReviewEvidence.Conflict.class, () -> correlation.observeThread(threadEvent, conflicting));
        assertEquals(beforeLast.retryAt(), assess(FIRST, null).retryAt());

        // Separate the observations by five seconds while staying within the same quiet period.
        jdbc.update("UPDATE pr_review_transitions SET occurred_at = occurred_at - interval '5 seconds' WHERE action = 'UPDATE_HEAD'");
        var last = event("pull_request", "synchronize", LAST);
        assertTrue(correlation.observeSynchronize(last, current(), head(LAST)));
        assertFalse(correlation.observeSynchronize(first, current(), head(LAST)));
        assertEquals(LAST, current().headSha());
        var afterLast = assess(LAST, null);
        assertEquals(WAITING_FOR_PUSHES, afterLast.status());
        assertTrue(afterLast.retryAt().isAfter(beforeLast.retryAt()));
        assertEquals(ReviewConflict.Code.STALE_HEAD,
                assertThrows(ReviewConflict.class, () -> assess(FIRST, changed(FIRST))).code());
        assertEquals(0, candidateCount());

        settlePushes();
        assertEquals(COMPARISON_REQUIRED, assess(LAST, changed(FIRST)).status());
        assertEquals(BASE, assess(LAST, null).baseSha());
        var assessment = new Assessment(head(LAST), current().version(), false, changed(LAST));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> correlation.assess(assessment));
            var two = workers.submit(() -> newCorrelator().assess(assessment));
            var a = one.get(5, TimeUnit.SECONDS);
            var b = two.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of(ELIGIBLE, ALREADY_ELIGIBLE), Set.of(a.status(), b.status()));
            assertEquals(a.candidateId(), b.candidateId());
            assertEquals(a.candidateId(), newCorrelator().assess(assessment).candidateId());
        }
        assertEquals(1, candidateCount());
        assertEquals(LAST, jdbc.queryForObject("SELECT head_sha FROM fix_review_candidates", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_requests", Integer.class));
    }

    @Test
    void historicalResolutionWithDismissedOccurrenceCannotHideCurrentOpenFinding() throws Exception {
        var normalizer = new GitHubReviewNormalizer();
        String fixture = inlineReviewFixture();
        waitingForFix(normalizer.normalize(UUID.randomUUID(), cycle, PR, BASE, TIME, fixture.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer', disposition_reason = 'Round decision'
                WHERE review_cycle_id = ? AND source_id = 'C1'
                """, cycle);
        push(FIRST);
        settlePushes();

        UUID currentRound = createCycle(FIRST, 2);
        String currentResponse = fixture.replace("\"headRefOid\": \"" + BASE, "\"headRefOid\": \"" + FIRST);
        assertTrue(ingestion.ingest(normalizer.normalize(UUID.randomUUID(), currentRound, PR, FIRST, TIME.plusSeconds(1),
                currentResponse.getBytes(StandardCharsets.UTF_8))));
        byte[] resolution = currentResponse.replace("\"isResolved\": false", "\"isResolved\": true")
                .getBytes(StandardCharsets.UTF_8);
        var historical = normalizer.normalizeForRound(UUID.randomUUID(), cycle, PR, BASE, FIRST, TIME.plusSeconds(2),
                resolution, Map.of(), Set.of());
        assertTrue(correlation.observeThread(event("pull_request_review_thread", "resolved", FIRST), historical));
        assertEquals(Map.of("status", "DISMISSED", "thread_resolved", true, "evidence_snapshot_id", historical.id()),
                jdbc.queryForMap("""
                    SELECT status, thread_resolved, evidence_snapshot_id FROM findings
                    WHERE review_cycle_id = ? AND source_id = 'C1'
                    """, cycle));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM findings WHERE review_cycle_id = ? AND source_id = 'C1'", String.class, currentRound));

        var decision = assess(FIRST, changed(FIRST));
        step(START_REVIEW, READY);
        assertTrue(ingestion.ingest(new Snapshot(UUID.randomUUID(), currentRound, PR, "github",
                FIRST, TIME.plusSeconds(3), List.of())));
        assertAll(
                () -> assertEquals(ELIGIBLE, decision.status()),
                () -> assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                        assertThrows(ReviewConflict.class, () -> step(BASELINE_CLEAN, READY)).code()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicalResolutionPreservesValidClosureUntilTheSourceRevisionChanges(boolean firstSeenHistorically) throws Exception {
        var normalizer = new GitHubReviewNormalizer();
        String fixture = inlineReviewFixture();
        String firstResponse = fixture.replace("\"headRefOid\": \"" + BASE, "\"headRefOid\": \"" + FIRST);
        byte[] roundResponse = firstResponse.getBytes(StandardCharsets.UTF_8);
        byte[] initialResponse = firstSeenHistorically ? roundResponse : fixture.getBytes(StandardCharsets.UTF_8);
        waitingForFix(normalizer.normalizeForRound(UUID.randomUUID(), cycle, PR, BASE,
                firstSeenHistorically ? FIRST : BASE, TIME, initialResponse, Map.of(), Set.of()));
        push(FIRST);
        UUID fixedRound = createCycle(FIRST, 2);
        assertTrue(ingestion.ingest(normalizer.normalize(UUID.randomUUID(), fixedRound, PR, FIRST, TIME.plusSeconds(1),
                roundResponse)));
        jdbc.update("""
                UPDATE findings SET status = 'VERIFIED_FIXED', evidence_sha = ?, verified_at = clock_timestamp(),
                    disposition_actor = 'maintainer', disposition_reason = 'Verified against the new commit'
                WHERE review_cycle_id = ? AND source_id = 'C1'
                """, LAST, fixedRound);
        push(LAST);
        settlePushes();
        assertEquals(NO_PENDING_FINDINGS, assess(LAST, changed(LAST)).status());

        var resolvedEvent = event("pull_request_review_thread", "resolved", LAST);
        String resolved = fixture.replace("\"headRefOid\": \"" + BASE, "\"headRefOid\": \"" + LAST)
                .replace("\"isResolved\": false", "\"isResolved\": true");
        byte[] resolution = resolved.getBytes(StandardCharsets.UTF_8);
        var historical = normalizer.normalizeForRound(UUID.randomUUID(), fixedRound, PR, FIRST, LAST,
                TIME.plusSeconds(2), resolution, Map.of(), Set.of());
        assertTrue(correlation.observeThread(resolvedEvent, historical));
        assertEquals(Map.of("status", "VERIFIED_FIXED", "evidence_sha", LAST, "thread_resolved", true,
                        "evidence_snapshot_id", historical.id()),
                jdbc.queryForMap("""
                    SELECT status, evidence_sha, thread_resolved, evidence_snapshot_id FROM findings
                    WHERE review_cycle_id = ? AND source_id = 'C1'
                    """, fixedRound));
        assertEquals(NO_PENDING_FINDINGS, assess(LAST, changed(LAST)).status());

        // The same metadata refresh on the superseded occurrence also leaves the closure intact.
        assertTrue(correlation.observeThread(resolvedEvent, normalizer.normalizeForRound(
                UUID.randomUUID(), cycle, PR, BASE, LAST, TIME.plusSeconds(3), resolution, Map.of(), Set.of())));
        assertEquals(NO_PENDING_FINDINGS, assess(LAST, changed(LAST)).status());
        step(START_REVIEW, READY);
        step(BASELINE_CLEAN, READY);
        assertEquals(LAST, current().baselineCleanSha());

        // An actual edit is a separate revision and still reopens the finding and revokes readiness.
        byte[] edited = resolved.replace("export a secret", "check the edge case").getBytes(StandardCharsets.UTF_8);
        assertTrue(correlation.observeThread(resolvedEvent, normalizer.normalizeForRound(
                UUID.randomUUID(), fixedRound, PR, FIRST, LAST, TIME.plusSeconds(4), edited, Map.of(), Set.of())));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM findings WHERE review_cycle_id = ? AND source_id = 'C1'", String.class, fixedRound));
        assertEquals(PullRequestReview.State.ACTION_REQUIRED, current().state());
        assertNull(current().baselineCleanSha());
    }

    @Test
    void freshReturnToSettledHeadRestartsQuietPeriodButDeliveryReplayDoesNot() {
        waitingForFix(inline(false));
        push(FIRST);
        settlePushes();
        assertEquals(COMPARISON_REQUIRED, assess(FIRST, null).status());

        var skipped = event("pull_request", "synchronize", LAST);
        var returned = event("pull_request", "synchronize", FIRST);
        assertFalse(correlation.observeSynchronize(skipped, current(), head(FIRST)));
        assertFalse(correlation.observeSynchronize(returned, current(), head(FIRST)));
        var waiting = assess(FIRST, changed(FIRST));
        assertEquals(WAITING_FOR_PUSHES, waiting.status());
        assertEquals(0, candidateCount());
        assertFalse(correlation.observeSynchronize(returned, current(), head(FIRST)));
        assertEquals(waiting.retryAt(), assess(FIRST, changed(FIRST)).retryAt());

        String finalHead = "d".repeat(40);
        push(finalHead);
        assertEquals(WAITING_FOR_PUSHES, assess(finalHead, changed(finalHead)).status());
        settlePushes();
        assertEquals(ELIGIBLE, assess(finalHead, changed(finalHead)).status());
        assertEquals(1, candidateCount());
        assertEquals(finalHead, jdbc.queryForObject("SELECT head_sha FROM fix_review_candidates", String.class));
    }

    @Test
    void resolvedThreadAndEmptyOrRevertedChangesLeaveTheFindingOpen() {
        waitingForFix(inline(false));
        var resolved = snapshot(1, inline(true));
        var event = event("pull_request_review_thread", "resolved", BASE);
        assertTrue(correlation.observeThread(event, resolved));
        assertFalse(correlation.observeThread(event, resolved));
        assertEquals(true, jdbc.queryForObject("SELECT thread_resolved FROM findings", Boolean.class));
        assertEquals(NO_NEW_CODE, assess(BASE, changed(BASE)).status());
        assertEquals(PullRequestReview.State.WAITING_FOR_FIX, current().state());

        push(FIRST);
        settlePushes();
        assertEquals(NO_NEW_CODE, assess(FIRST, new Comparison(BASE, FIRST, false)).status());
        push(BASE);
        settlePushes();
        assertEquals(NO_NEW_CODE, assess(BASE, changed(BASE)).status());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertNull(jdbc.queryForObject("SELECT verified_at FROM findings", java.sql.Timestamp.class));
        assertEquals(0, candidateCount());
    }

    @Test
    void confirmedReturnToCurrentHeadFencesAnOlderInFlightHeadUpdate() {
        waitingForFix(inline(false));
        var expected = current();
        var older = event("pull_request", "synchronize", FIRST);
        var returned = event("pull_request", "synchronize", BASE);
        assertFalse(correlation.observeSynchronize(returned, expected, head(BASE)));
        assertEquals(expected.version() + 1, current().version());
        assertFalse(correlation.observeSynchronize(returned, current(), head(BASE)));
        assertEquals(expected.version() + 1, current().version());
        assertEquals(ReviewConflict.Code.STALE_VERSION,
                assertThrows(ReviewConflict.class, () -> correlation.observeSynchronize(older, expected, head(FIRST))).code());
        assertEquals(BASE, current().headSha());
        assertEquals(NO_NEW_CODE, assess(BASE, null).status());
        assertEquals(0, candidateCount());
    }

    @Test
    void confirmedReturnToCurrentHeadInvalidatesExistingReadiness() {
        step(BASELINE_CLEAN, READY);
        step(DEEP_CLEAN, READY);
        step(MARK_READY_FOR_HUMAN, READY);
        assertFalse(correlation.observeSynchronize(
                event("pull_request", "synchronize", FIRST), current(), head(BASE)));
        var returned = event("pull_request", "synchronize", BASE);
        assertFalse(correlation.observeSynchronize(returned, current(), head(BASE)));

        var state = current();
        assertEquals(BASE, state.headSha());
        assertEquals(PullRequestReview.State.WAITING_FOR_REVIEW, state.state());
        assertNull(state.baselineCleanSha());
        assertNull(state.deepCleanSha());
        assertFalse(correlation.observeSynchronize(returned, state, head(BASE)));
        assertEquals(state, current());
    }

    @Test
    void generalOnlyFeedbackNeedsCurrentExplicitEvidenceAndStillBlocksClean() {
        var original = waitingForFix(general("1"));
        push(FIRST);
        settlePushes();
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(FIRST, changed(FIRST)).status());
        dispose(original, GeneralFeedback.Action.ACKNOWLEDGE, null);
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(FIRST, changed(FIRST)).status());
        dispose(original, GeneralFeedback.Action.CLAIM_FIXED, LAST);
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(FIRST, changed(FIRST)).status());
        dispose(original, GeneralFeedback.Action.CLAIM_FIXED, FIRST);
        assertEquals(ELIGIBLE, assess(FIRST, changed(FIRST)).status());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertNull(jdbc.queryForObject("SELECT verified_at FROM findings", java.sql.Timestamp.class));

        var edited = snapshot(1, general("2"));
        assertTrue(ingestion.ingest(edited));
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(FIRST, changed(FIRST)).status());
        dispose(edited, GeneralFeedback.Action.CLAIM_FIXED, FIRST);
        assertEquals(ALREADY_ELIGIBLE, assess(FIRST, changed(FIRST)).status());
        step(START_REVIEW, READY);
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> step(BASELINE_CLEAN, READY)).code());
    }

    @Test
    void dismissalClearsOnlyThatFindingAndReturnsNoCandidateWithoutOtherPendingWork() {
        var original = waitingForFix(general("1"));
        push(FIRST);
        settlePushes();
        dispose(original, GeneralFeedback.Action.DISMISS, null);
        assertEquals(NO_PENDING_FINDINGS, assess(FIRST, changed(FIRST)).status());
        assertFalse(correlation.observeSynchronize(event("pull_request", "synchronize", FIRST), current(), head(FIRST)));
        settlePushes();
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(FIRST, changed(FIRST)).status());
        dispose(original, GeneralFeedback.Action.DISMISS, null);
        push(LAST);
        settlePushes();
        assertEquals(GENERAL_DISPOSITION_REQUIRED, assess(LAST, changed(LAST)).status());
        assertEquals(0, candidateCount());
    }

    @Test
    void staleVersionPauseExistingRequestAndRevokedAuthorizationBlockEligibility() {
        waitingForFix(inline(false));
        push(FIRST);
        settlePushes();
        var assessment = new Assessment(head(FIRST), current().version(), false, changed(FIRST));
        step(PAUSE, READY);
        assertEquals(ReviewConflict.Code.STALE_VERSION,
                assertThrows(ReviewConflict.class, () -> correlation.assess(assessment)).code());
        assertEquals(NOT_REVIEWABLE, assess(FIRST, changed(FIRST)).status());
        step(RESUME, READY);

        var manual = new ReviewRequest(PR, FIRST, "BASELINE", "github", ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
        new JdbcReviewRequestStore(jdbc, transactions).reserve(manual, 1, 6,
                () -> authorization.requireAuthorized(PR.scope()));
        assertEquals(ALREADY_REQUESTED, assess(FIRST, changed(FIRST)).status());
        jdbc.update("UPDATE installation_repositories SET enabled = false");
        var revoked = new Assessment(head(FIRST), assessment.expectedVersion(), false, changed(FIRST));
        assertThrows(AuthorizationDenied.class, () -> correlation.assess(revoked));
        assertEquals(0, candidateCount());
    }

    @Test
    void historicalReadCannotReopenALaterRoundsClosedFindingUntilItsRevisionChanges() {
        waitingForFix(inline(false));
        jdbc.update("UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer', disposition_reason = 'Round decision' WHERE review_cycle_id = ?", cycle);
        push(FIRST);
        settlePushes();
        UUID currentRound = createCycle(FIRST, 2);
        Source late = inlineSource("late", FIRST, "2");
        assertTrue(ingestion.ingest(new Snapshot(UUID.randomUUID(), currentRound, PR, "github", FIRST,
                TIME.plusSeconds(1), List.of(late))));
        jdbc.update("UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer', disposition_reason = 'Round decision' WHERE review_cycle_id = ? AND source_id = 'late'", currentRound);
        assertEquals(NO_PENDING_FINDINGS, assess(FIRST, changed(FIRST)).status());

        // An older round's read emits every PR comment, including one first posted in the later round.
        var resolved = event("pull_request_review_thread", "resolved", FIRST);
        assertTrue(correlation.observeThread(resolved, new Snapshot(UUID.randomUUID(), cycle, PR, "github", BASE, FIRST,
                TIME.plusSeconds(2), List.of(inline(true), late))));
        assertEquals(Map.of("status", "OPEN"), jdbc.queryForMap("""
                SELECT status FROM findings WHERE review_cycle_id = ? AND source_id = 'late' AND round_observed_at IS NULL
                """, cycle));
        assertEquals(NO_PENDING_FINDINGS, assess(FIRST, changed(FIRST)).status());

        // An edit is a new revision without a same-head closure, so it blocks again.
        assertTrue(correlation.observeThread(resolved, new Snapshot(UUID.randomUUID(), cycle, PR, "github", BASE, FIRST,
                TIME.plusSeconds(3), List.of(inline(true), inlineSource("late", FIRST, "3")))));
        assertEquals(ELIGIBLE, assess(FIRST, changed(FIRST)).status());
    }

    @Test
    void historicalReadAtAHeadTheLifecycleLeftIsRejected() {
        waitingForFix(inline(false));
        push(FIRST);
        push(LAST);
        var resolved = event("pull_request_review_thread", "resolved", FIRST);
        assertThrows(ReviewEvidence.Conflict.class, () -> correlation.observeThread(resolved, new Snapshot(
                UUID.randomUUID(), cycle, PR, "github", BASE, FIRST, TIME.plusSeconds(1), List.of(inline(true)))));
        assertTrue(correlation.observeThread(resolved, new Snapshot(
                UUID.randomUUID(), cycle, PR, "github", BASE, LAST, TIME.plusSeconds(2), List.of(inline(true)))));
    }

    @Test
    void returnToAnEarlierCandidateHeadOpensANewRequestEpochForTheCurrentBase() {
        waitingForFix(inline(false));
        push(FIRST);
        settlePushes();
        var first = assess(FIRST, changed(FIRST));
        assertEquals(ELIGIBLE, first.status());
        var requests = new JdbcReviewRequestStore(jdbc, transactions);
        Runnable guards = () -> authorization.requireAuthorized(PR.scope());
        var originalRequest = new ReviewRequest(PR, FIRST, "BASELINE", "github",
                ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
        assertEquals(0, JdbcFixCorrelationStore.headEpoch(jdbc, PR, FIRST));
        var original = requests.reserve(originalRequest, 1, 6, guards);
        assertTrue(original.created());
        step(START_REVIEW, READY);
        push(LAST);
        step(START_REVIEW, READY);
        step(BLOCKING_FINDINGS, new Gates(true, false, false, false, true, Ci.UNKNOWN));
        step(AWAIT_FIX, READY);
        var returnEvent = event("pull_request", "synchronize", FIRST);
        assertTrue(correlation.observeSynchronize(returnEvent, current(), head(FIRST)));
        long returnVersion = current().version();
        var comparison = new Comparison(LAST, FIRST, true);
        assertEquals(WAITING_FOR_PUSHES, assess(FIRST, comparison).status());
        settlePushes();

        var returned = assess(FIRST, comparison);
        assertEquals(ELIGIBLE, returned.status());
        assertEquals(LAST, returned.baseSha());
        assertNotEquals(first.candidateId(), returned.candidateId());
        assertEquals(LAST, jdbc.queryForObject("SELECT base_sha FROM fix_review_candidates WHERE id = ?",
                String.class, returned.candidateId()));
        assertTrue(correlation.observeSynchronize(returnEvent, current(), head(FIRST)));
        var retry = assess(FIRST, comparison);
        assertEquals(ALREADY_ELIGIBLE, retry.status());
        assertEquals(returned.candidateId(), retry.candidateId());

        long epoch = returned.headEpoch();
        assertEquals(returnVersion, epoch);
        assertEquals(epoch, retry.headEpoch());
        var stale = new ReviewRequest(PR, FIRST, "BASELINE", "codex",
                ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
        assertEquals(ReviewRequestFailure.Code.STALE_HEAD,
                assertThrows(ReviewRequestFailure.class, () -> requests.reserve(stale, 1, 6, guards)).code());
        assertEquals(original.id(), requests.reserve(originalRequest, 1, 6, guards).id());
        assertEquals(ReviewRequestFailure.Code.STALE_HEAD, assertThrows(ReviewRequestFailure.class,
                () -> requests.send(originalRequest, original.id(), guards,
                        () -> fail("An earlier epoch must not reach the provider"))).code());

        var renewed = new ReviewRequest(PR, FIRST, "BASELINE", "github",
                ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false, epoch);
        assertEquals(ReviewRequestFailure.Code.LIMIT_REACHED,
                assertThrows(ReviewRequestFailure.class, () -> requests.reserve(renewed, 1, 1, guards)).code());
        var renewedReservation = requests.reserve(renewed, 1, 6, guards);
        assertTrue(renewedReservation.created());
        var renewedReplay = requests.reserve(renewed, 1, 6, guards);
        assertFalse(renewedReplay.created());
        assertEquals(renewedReservation.id(), renewedReplay.id());
        assertEquals(ALREADY_REQUESTED, assess(FIRST, comparison).status());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM review_requests", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendRejectsEarlierHeadBeforeTheRemoteReturnIsJournaled(boolean departBetweenSendPhases) {
        var requests = new JdbcReviewRequestStore(jdbc, transactions);
        Runnable guards = () -> authorization.requireAuthorized(PR.scope());
        var request = new ReviewRequest(PR, BASE, "BASELINE", "github",
                ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
        var reservation = requests.reserve(request, 1, 6, guards);
        var scheduled = new AtomicBoolean();
        Runnable sendGuards = () -> {
            guards.run();
            if (departBetweenSendPhases && scheduled.compareAndSet(false, true)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { push(FIRST); }
                });
            }
        };
        if (!departBetweenSendPhases) push(FIRST);
        // A remote return to BASE during this gap would satisfy a remote SHA check.
        assertEquals(ReviewRequestFailure.Code.STALE_HEAD, assertThrows(ReviewRequestFailure.class,
                () -> requests.send(request, reservation.id(), sendGuards,
                        () -> new Receipt(42, true))).code());
        assertEquals(FIRST, current().headSha());
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT state FROM review_requests WHERE id = ?",
                String.class, reservation.id()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void v8ReservationForAnAlreadyJournaledReturnSurvivesUpgrade(boolean tiedTimestamp) {
        var rootJdbc = jdbc;
        String schema = "request_visit_upgrade";
        String url = POSTGRES.getJdbcUrl();
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("8").load().migrate();
            configure(dataSource);
            createReviewingPr();
            push(FIRST);
            step(START_REVIEW, READY);
            step(BLOCKING_FINDINGS, new Gates(true, false, false, false, true, Ci.UNKNOWN));
            step(AWAIT_FIX, READY);
            push(BASE);
            long epoch = current().version();
            settlePushes();
            UUID requestId = UUID.randomUUID();
            var legacy = new ReviewRequest(PR, BASE, "BASELINE", "github", ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
            Timestamp reservedAt = tiedTimestamp
                    ? jdbc.queryForObject("SELECT occurred_at FROM pr_review_transitions WHERE resulting_version = ?", Timestamp.class, epoch)
                    : jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
            jdbc.update("""
                    INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                        head_sha, stage, provider, actor_id, state, comment_id, created_at, completed_at)
                    VALUES (?, ?, 101, 1001, 7, ?, 'BASELINE', 'github', 1, 'COMMENT_CREATED', 42, ?, clock_timestamp())
                    """, requestId, legacy.key(), BASE, reservedAt);

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
            assertTrue(ingestion.ingest(snapshot(0, inline(false))));
            assertEquals(ALREADY_REQUESTED, assess(BASE, new Comparison(FIRST, BASE, true)).status());
            var currentRequest = new ReviewRequest(PR, BASE, "BASELINE", "github",
                    ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false, epoch);
            var duplicate = new JdbcReviewRequestStore(jdbc, transactions).reserve(currentRequest, 1, 6,
                    () -> authorization.requireAuthorized(PR.scope()));
            assertFalse(duplicate.created());
            assertEquals(requestId, duplicate.id());
            assertEquals(legacy.key(), jdbc.queryForObject("SELECT request_key FROM review_requests WHERE id = ?",
                    String.class, requestId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM review_requests", Integer.class));

            jdbc.update("""
                    UPDATE findings SET status = 'DISMISSED', disposition_actor = 'maintainer', disposition_reason = 'Fixture'
                    WHERE review_cycle_id = ?
                    """, cycle);
            step(START_REVIEW, READY);
            Instant observed = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            var review = new Source("review", Kind.REVIEW, "review", null, null, "reviewer", BASE, null,
                    "1".repeat(64), observed, "COMMENTED", null, null, null, null, null, Severity.UNKNOWN, Feedback.NONE);
            var snapshot = new Snapshot(UUID.randomUUID(), cycle, PR, "github", BASE, observed.plusSeconds(1), List.of(review));
            assertTrue(ingestion.ingest(snapshot));
            var run = new ReviewOutcome.Run(PR, BASE, "github", ReviewOutcome.Stage.BASELINE, requestId, cycle, "github", "reviewer");
            var completion = new ReviewOutcome.Completion(run, "review", ReviewOutcome.Status.COMPLETED, ReviewOutcome.Conclusion.NO_FINDINGS);
            var outcomes = new AssessReviewOutcome(new JdbcReviewOutcomeStore(jdbc, new JdbcReviewStateStore(jdbc, transactions)), authorization);
            assertEquals(tiedTimestamp ? ReviewOutcome.Verdict.UNKNOWN : ReviewOutcome.Verdict.CLEAN_FOR_SHA,
                    outcomes.assess(new ReviewOutcome.Assessment(run, current().version(), completion, snapshot.id())).verdict());
        } finally {
            rootJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void sameHeadConfirmationOpensOneNewRequestEpoch() {
        waitingForFix(inline(false));
        push(FIRST);
        settlePushes();
        var requests = new JdbcReviewRequestStore(jdbc, transactions);
        Runnable guards = () -> authorization.requireAuthorized(PR.scope());
        var legacy = new ReviewRequest(PR, FIRST, "BASELINE", "github", ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false);
        assertEquals(legacy.key(), new ReviewRequest(PR, FIRST, "BASELINE", "github",
                ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false, 0).key());
        assertTrue(requests.reserve(legacy, 1, 6, guards).created());
        step(START_REVIEW, READY);

        assertFalse(correlation.observeSynchronize(event("pull_request", "synchronize", FIRST), current(), head(FIRST)));
        settlePushes();
        assertEquals(PullRequestReview.State.WAITING_FOR_REVIEW, current().state());
        assertEquals(ELIGIBLE, assess(FIRST, changed(FIRST)).status());
        long epoch = JdbcFixCorrelationStore.headEpoch(jdbc, PR, FIRST);
        assertTrue(epoch > 0);
        assertFalse(requests.reserve(legacy, 1, 6, guards).created());
        assertEquals(ReviewRequestFailure.Code.STALE_HEAD, assertThrows(ReviewRequestFailure.class, () -> requests.reserve(
                new ReviewRequest(PR, FIRST, "BASELINE", "github", ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false, epoch + 1),
                1, 6, guards)).code());
        var renewed = new ReviewRequest(PR, FIRST, "BASELINE", "github", ReviewRequest.Trigger.PR_SYNCHRONIZE, 1, false, epoch);
        assertEquals(legacy.key() + "/" + epoch, renewed.key());
        assertTrue(requests.reserve(renewed, 1, 6, guards).created());
        assertFalse(requests.reserve(renewed, 1, 6, guards).created());
        assertEquals(ALREADY_REQUESTED, assess(FIRST, changed(FIRST)).status());
    }

    private Source inlineSource(String id, String sha, String digit) {
        return new Source(id, Kind.REVIEW_COMMENT, null, "thread-" + id, null, "reviewer", sha, sha,
                digit.repeat(64), TIME, null, false, false, "Example.java", 2, 2, Severity.HIGH);
    }

    private CorrelateFixes newCorrelator() {
        return new CorrelateFixes(authorization, ingestion,
                new JdbcFixCorrelationStore(jdbc, new JdbcReviewStateStore(jdbc, transactions)));
    }

    private UUID createCycle(String headSha, int iteration) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github', ?)
                """, id, headSha, iteration);
        return id;
    }

    private String inlineReviewFixture() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/github/review-snapshot.json"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("Review summary, not a clean verdict", "");
        }
    }

    private PullRequestReview current() { return lifecycle.find(PR).orElseThrow(); }

    private void step(ReviewTransition.Action action, Gates gates) {
        var state = current();
        lifecycle.apply(PR, new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action, null, gates));
    }

    private Snapshot waitingForFix(Source source) {
        return waitingForFix(snapshot(0, source));
    }

    private Snapshot waitingForFix(Snapshot snapshot) {
        assertTrue(ingestion.ingest(snapshot));
        step(BLOCKING_FINDINGS, new Gates(true, false, false, false, true, Ci.UNKNOWN));
        step(AWAIT_FIX, READY);
        return snapshot;
    }

    private Snapshot snapshot(long seconds, Source source) {
        return new Snapshot(UUID.randomUUID(), cycle, PR, "github", BASE, TIME.plusSeconds(seconds), List.of(source));
    }

    private Source inline(boolean resolved) {
        return new Source("comment", Kind.REVIEW_COMMENT, null, "thread", null, "reviewer", BASE, BASE,
                "1".repeat(64), TIME, null, resolved, false, "Example.java", 1, 1, Severity.HIGH);
    }

    private Source general(String digest) {
        return new Source("comment", Kind.ISSUE_COMMENT, null, null, null, "reviewer", null, null, digest.repeat(64),
                TIME, null, null, null, null, null, null, Severity.UNKNOWN, Feedback.ACTIONABLE);
    }

    private void dispose(Snapshot snapshot, GeneralFeedback.Action action, String evidenceSha) {
        var finding = jdbc.queryForObject("SELECT id FROM finding_identities", UUID.class);
        var state = current();
        assertTrue(dispositions.dispose(PR, new Disposition(UUID.randomUUID(), finding, snapshot.id(),
                state.headSha(), state.version(), action, "author", "Explicit fixture decision", evidenceSha)));
    }

    private Event event(String type, String action, String sha) {
        var lease = new Lease(Queue.INBOX, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        return new Event(lease, PR.scope(), type, action, PR.number(), sha);
    }

    private Head head(String sha) { return new Head(PR, sha, true, false); }
    private Comparison changed(String head) { return new Comparison(BASE, head, true); }

    private void push(String sha) {
        assertTrue(correlation.observeSynchronize(event("pull_request", "synchronize", sha), current(), head(sha)));
    }

    private Decision assess(String sha, Comparison comparison) {
        return correlation.assess(new Assessment(head(sha), current().version(), false, comparison));
    }

    private void settlePushes() {
        jdbc.update("""
                UPDATE pr_review_transitions SET occurred_at = clock_timestamp() - interval '1 minute'
                WHERE action IN ('UPDATE_HEAD', 'CONFIRM_HEAD')
                """);
    }

    private int candidateCount() {
        return jdbc.queryForObject("SELECT count(*) FROM fix_review_candidates", Integer.class);
    }
}
