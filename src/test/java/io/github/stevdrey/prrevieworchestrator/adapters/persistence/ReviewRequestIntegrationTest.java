package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider.Result.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.UNSUPPORTED_TRIGGER;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.CAPABILITY_UNVERIFIED;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.LIMIT_REACHED;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.STALE_HEAD;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.CI_NOT_PASSED;

import io.github.stevdrey.prrevieworchestrator.adapters.github.*;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCodexBudget.Terms;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.ReviewLifecycle;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReviewRequestIntegrationTest {
    static final RepositoryScope SCOPE = new RepositoryScope(101, 1001);
    static final PullRequestId PR = new PullRequestId(SCOPE, 7);
    static final String HEAD = "a".repeat(40);
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            Objects.requireNonNull(System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));
    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    RepositoryAuthorization authorization;
    GitHubCodexComments comments;
    CodexApproval approval;
    AtomicInteger effects;

    @BeforeEach void setup() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.execute("TRUNCATE sandbox_codex_budget_limits");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (202, 2002, true)");
        authorization = new RepositoryAuthorization(Set.of(SCOPE), new JdbcInstallationAuthorization(jdbc));
        comments = mock(GitHubCodexComments.class);
        approval = mock(CodexApproval.class);
        effects = new AtomicInteger();
        when(comments.create(any(), eq(42L), any())).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run();
            return new ReviewRequestStore.Receipt(1000L + effects.incrementAndGet(), true);
        });
    }
    ReviewRequest request(String head) {
        return new ReviewRequest(PR, head, "BASELINE", "codex", ReviewRequest.Trigger.PR_SYNCHRONIZE, 123, false);
    }
    JdbcReviewRequestStore store() { return new JdbcReviewRequestStore(jdbc, new DataSourceTransactionManager(dataSource)); }
    CodexReviewAdapter adapter() { return new CodexReviewAdapter(authorization, store(), approval, comments, 42, 6); }
    ReviewLifecycle lifecycle() {
        return new ReviewLifecycle(new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(dataSource)), authorization);
    }
    void advanceTo(String sha) {
        var lifecycle = lifecycle();
        var current = lifecycle.find(PR).orElse(null);
        var state = current == null ? lifecycle.create(PR, sha) : lifecycle.apply(PR, new ReviewTransition(
                UUID.randomUUID(), current.version(), current.headSha(), ReviewTransition.Action.UPDATE_HEAD, sha,
                new ReviewTransition.Gates(true, false, false, true, false, ReviewTransition.Ci.UNKNOWN)));
        reconcileCi(state, ReviewTransition.Ci.PASSED);
    }
    void reconcileCi(PullRequestReview state, ReviewTransition.Ci status) {
        var check = new CiEvidence.Check(2, "build");
        lifecycle().reconcileCi(state.version(), new CiEvidence(UUID.randomUUID(), PR, state.headSha(),
                Set.of(check), List.of(new CiEvidence.Run(check, state.headSha(), status)), true));
    }
    int rows() { return jdbc.queryForObject("SELECT count(*) FROM review_requests", Integer.class); }
    void code(ReviewRequestFailure.Code code, Runnable action) {
        var failure = assertThrows(ReviewRequestFailure.class, action::run);
        assertEquals(code, failure.code());
        assertNull(failure.getCause());
    }

    @Test void duplicateAcrossEventsAndRestartHasOnePermanentScopedRequest() {
        assertEquals(COMMENT_CREATED, adapter().request(request(HEAD)));
        var opened = new ReviewRequest(PR, HEAD, "BASELINE", "codex", ReviewRequest.Trigger.PR_OPENED, 987, false);
        assertEquals(DUPLICATE, adapter().request(opened));
        assertEquals(1, effects.get());
        assertEquals(1, rows());
        assertEquals(1001L, jdbc.queryForObject("SELECT comment_id FROM review_requests", Long.class));
        verify(approval, times(4)).requirePermitted(any()); // reserve, consume, send, actual boundary
    }

    @Test void competingWorkersReserveOnceBeforeAnyRemoteEffect() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<ReviewProvider.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); return adapter().request(request(HEAD)); }));
            start.countDown();
            int created = 0;
            for (var future : futures) {
                var result = future.get(15, TimeUnit.SECONDS);
                if (result == COMMENT_CREATED) created++;
                else assertTrue(result == DUPLICATE || result == PENDING || result == OUTCOME_UNKNOWN);
            }
            assertEquals(1, created);
        }
        assertEquals(1, rows());
        assertEquals(1, effects.get());
    }

    @Test void lostResponseAndCrashBeforeSendCannotRedriveAfterRestart() {
        when(comments.create(any(), anyLong(), any())).thenAnswer(call -> {
            effects.incrementAndGet();
            throw new ReviewRequestFailure(ReviewRequestFailure.Code.OUTCOME_UNKNOWN);
        });
        assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
        assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
        assertEquals(1, effects.get());
        assertEquals("ATTEMPTED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        var crash = request("b".repeat(40));
        advanceTo(crash.headSha());
        store().reserve(crash, 42, 6, () -> authorization.requireAuthorized(SCOPE), () -> {});
        assertEquals(PENDING, adapter().request(crash));
        assertEquals(1, effects.get());
    }

    @Test void permitItselfCannotBeReusedEvenAfterRollbackOfEffectTransaction() {
        var r = request(HEAD);
        var reservation = store().reserve(r, 42, 6, () -> authorization.requireAuthorized(SCOPE), () -> {});
        code(ReviewRequestFailure.Code.OUTCOME_UNKNOWN, () -> store().send(r, reservation.id(), () -> authorization.requireAuthorized(SCOPE), () -> {
            effects.incrementAndGet();
            throw new ReviewRequestFailure(ReviewRequestFailure.Code.OUTCOME_UNKNOWN);
        }));
        code(ReviewRequestFailure.Code.OUTCOME_UNKNOWN, () -> store().send(r, reservation.id(), () -> authorization.requireAuthorized(SCOPE), () -> {
            effects.incrementAndGet(); return new ReviewRequestStore.Receipt(1001, true);
        }));
        assertEquals(1, effects.get());
    }

    @Test void botCommentsAndReviewEventsNeverCreateReservationsEvenForNewSha() {
        for (var trigger : ReviewRequest.Trigger.values()) {
            var bot = new ReviewRequest(PR, HEAD, "BASELINE", "codex", trigger, 99, true);
            assertEquals(IGNORED, adapter().request(bot));
        }
        for (var trigger : List.of(ReviewRequest.Trigger.COMMENT, ReviewRequest.Trigger.REVIEW)) {
            // Includes the consenting user posting the orchestrator's own trigger, plus provider replies.
            assertEquals(IGNORED, adapter().request(new ReviewRequest(PR, HEAD, "BASELINE", "codex", trigger, 42, false)));
        }
        assertEquals(0, rows());
        verifyNoInteractions(comments, approval);
    }

    @Test void unsupportedProviderStageAndUnverifiedCapabilityFailClosed() {
        code(UNSUPPORTED_TRIGGER, () -> adapter().request(new ReviewRequest(PR, HEAD, "DEEP", "codex", ReviewRequest.Trigger.PR_READY, 123, false)));
        code(UNSUPPORTED_TRIGGER, () -> adapter().request(new ReviewRequest(PR, HEAD, "BASELINE", "other", ReviewRequest.Trigger.PR_READY, 123, false)));
        doThrow(new ReviewRequestFailure(CAPABILITY_UNVERIFIED)).when(approval).requirePermitted(any());
        code(CAPABILITY_UNVERIFIED, () -> adapter().request(request(HEAD)));
        assertEquals(0, rows());
        verifyNoInteractions(comments);
    }

    @Test void revokedInstallationAndForeignScopeNeverSend() {
        var foreign = new ReviewRequest(new PullRequestId(new RepositoryScope(202, 2002), 7), HEAD,
                "BASELINE", "codex", ReviewRequest.Trigger.PR_READY, 123, false);
        assertThrows(AuthorizationDenied.class, () -> adapter().request(foreign));
        jdbc.update("UPDATE installations SET status = 'REVOKED' WHERE installation_id = 101");
        assertThrows(AuthorizationDenied.class, () -> adapter().request(request(HEAD)));
        assertEquals(0, rows());
        verifyNoInteractions(comments);
    }

    @Test void revocationAfterReservationStillPreventsWrite() {
        var r = request(HEAD);
        var reserved = store().reserve(r, 42, 6, () -> authorization.requireAuthorized(SCOPE), () -> {});
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        assertThrows(AuthorizationDenied.class, () -> store().send(r, reserved.id(),
                () -> authorization.requireAuthorized(SCOPE), () -> { effects.incrementAndGet(); return new ReviewRequestStore.Receipt(1, true); }));
        assertEquals(0, effects.get());
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
    }

    @Test void withdrawnBudgetBeforePostConsumesThePermitWithoutSending() {
        var checks = new AtomicInteger();
        doAnswer(call -> {
            if (checks.incrementAndGet() == 4) {
                throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            }
            return null;
        }).when(approval).requirePermitted(any());
        var r = request(HEAD);
        code(ReviewRequestFailure.Code.POLICY_DENIED, () -> adapter().request(r));
        assertEquals(NOT_SENT, adapter().request(r));
        assertEquals(4, checks.get());
        assertEquals(0, effects.get());
        assertEquals(1, rows());
    }

    @Test void operatorCommandRequiresTheSandboxAndConsentingActorAndReusesTheDurableKey() {
        var operator = new ReviewRequest(PR, HEAD, "BASELINE", "codex", ReviewRequest.Trigger.SANDBOX_OPERATOR, 42, false);
        code(UNSUPPORTED_TRIGGER, () -> adapter().request(operator));
        var sandbox = new CodexReviewAdapter(authorization, store(), approval, comments, 42, 1, true);
        var otherActor = new ReviewRequest(PR, HEAD, "BASELINE", "codex", operator.trigger(), 43, false);
        code(UNSUPPORTED_TRIGGER, () -> sandbox.request(otherActor));
        assertEquals(0, rows());
        assertEquals(COMMENT_CREATED, sandbox.request(operator));
        assertEquals(DUPLICATE, sandbox.request(operator));
        assertEquals(DUPLICATE, adapter().request(request(HEAD)));
        assertEquals(1, rows());
        assertEquals(1, effects.get());
    }

    @Test void sandboxBudgetRollsBackTheRequestAndAllCountersOnLocalLimitDenial() {
        var store = budgetStore(new Terms("fixture_units", 4, 5, 9, 20));
        var original = request(HEAD);
        var samePr = new ReviewRequest(PR, HEAD, "DEEP", "codex", ReviewRequest.Trigger.PR_READY, 42, false);
        var secondPr = budgetRequest(new PullRequestId(SCOPE, 8));
        var thirdPr = budgetRequest(new PullRequestId(SCOPE, 9));

        assertTrue(reserveBudget(store, original).created());
        assertFalse(reserveBudget(store, original).created());
        code(ReviewRequestFailure.Code.POLICY_DENIED, () -> reserveBudget(store, samePr));
        assertEquals(4, reservedUnits("global"));
        assertEquals(4, reservedUnits("installation:101"));
        assertTrue(reserveBudget(store, secondPr).created());
        code(ReviewRequestFailure.Code.POLICY_DENIED, () -> reserveBudget(store, thirdPr));
        assertEquals(8, reservedUnits("global"));
        assertEquals(8, reservedUnits("installation:101"));
        assertEquals(4, reservedUnits("pr:101:1001:7"));
        assertEquals(2, rows());
        assertEquals(2, heldBudgets());
    }

    @Test void competingInstallationsCannotExceedTheSharedSandboxBudget() throws Exception {
        var terms = new Terms("fixture_units", 4, 8, 8, 6);
        var first = request(HEAD);
        var second = budgetRequest(new PullRequestId(new RepositoryScope(202, 2002), 7));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (var request : List.of(first, second)) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        return reserveBudget(budgetStore(terms), request).created();
                    } catch (ReviewRequestFailure denied) {
                        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, denied.code());
                        return false;
                    }
                }));
            }
            start.countDown();
            assertNotEquals(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
        }
        assertEquals(4, reservedUnits("global"));
        assertEquals(1, rows());
        assertEquals(1, heldBudgets());
    }

    @Test void unknownOutcomeKeepsTheBudgetHeldAndCannotBeSentAgain() {
        var terms = new Terms("fixture_units", 4, 5, 9, 20);
        var original = request(HEAD);
        when(comments.create(eq(original), eq(42L), any())).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run();
            effects.incrementAndGet();
            throw new ReviewRequestFailure(ReviewRequestFailure.Code.OUTCOME_UNKNOWN);
        });
        var adapter = new CodexReviewAdapter(authorization, budgetStore(terms), approval, comments, 42, 1);
        assertEquals(OUTCOME_UNKNOWN, adapter.request(original));
        var restarted = new CodexReviewAdapter(authorization, budgetStore(terms), approval, comments, 42, 1);
        assertEquals(OUTCOME_UNKNOWN, restarted.request(original));
        assertEquals("ATTEMPTED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        assertEquals(4, reservedUnits("global"));
        assertEquals(1, effects.get());
        assertEquals(1, heldBudgets());
    }

    @Test void aRequestWithoutAMatchingBudgetHoldCannotReachTheEffect() {
        var original = request(HEAD);
        var past = reserveBudget(store(), original);
        var budget = budgetStore(new Terms("fixture_units", 4, 5, 9, 20));
        code(ReviewRequestFailure.Code.POLICY_DENIED, () -> budget.send(original, past.id(),
                () -> authorization.requireAuthorized(SCOPE), () -> fail("Missing budget allowed the effect")));
        assertEquals(NOT_SENT, adapter().request(original));
        assertEquals(0, heldBudgets());
    }

    private ReviewRequest budgetRequest(PullRequestId pr) {
        return new ReviewRequest(pr, HEAD, "BASELINE", "codex", ReviewRequest.Trigger.PR_READY, 42, false);
    }

    private JdbcReviewRequestStore budgetStore(Terms terms) {
        var manager = new DataSourceTransactionManager(dataSource);
        return new JdbcReviewRequestStore(jdbc, manager, new JdbcReviewStateStore(jdbc, manager),
                new JdbcSandboxCodexBudget(jdbc, terms));
    }

    private ReviewRequestStore.Reservation reserveBudget(JdbcReviewRequestStore store, ReviewRequest request) {
        var fixtureAuthorization = new RepositoryAuthorization(Set.of(SCOPE, new RepositoryScope(202, 2002)),
                new JdbcInstallationAuthorization(jdbc));
        return store.reserve(request, 42, 6, () -> fixtureAuthorization.requireAuthorized(request.pr().scope()), () -> {});
    }

    private long reservedUnits(String key) {
        return jdbc.queryForObject("SELECT reserved FROM sandbox_codex_budget_limits WHERE scope_key = ?", Long.class, key);
    }

    private int heldBudgets() {
        return jdbc.queryForObject("SELECT count(*) FROM sandbox_codex_budget_reservations", Integer.class);
    }

    @Test void acceptedCommentWithDeferredCommitFailureRetainsConsumedPermit() {
        jdbc.execute("""
                CREATE FUNCTION reject_review_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.state = 'COMMENT_CREATED' THEN RAISE EXCEPTION 'fixture-only-secret'; END IF;
                RETURN NEW; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_review_receipt AFTER UPDATE ON review_requests
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_review_receipt()
                """);
        try {
            assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
            assertEquals(1, effects.get());
            assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
            assertEquals(1, effects.get());
            assertEquals("ATTEMPTED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER fail_review_receipt ON review_requests");
            jdbc.execute("DROP FUNCTION reject_review_receipt()");
        }
    }

    @Test void provablyUnsentPreflightFailureIsTerminalNotSentAndNeverRedriven() {
        when(comments.create(any(), anyLong(), any())).thenThrow(new ReviewRequestFailure(STALE_HEAD));
        code(STALE_HEAD, () -> adapter().request(request(HEAD)));
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        assertEquals(NOT_SENT, adapter().request(request(HEAD)));
        verify(comments, times(1)).create(any(), anyLong(), any());
        assertEquals(1, rows());
    }

    @Test void unconfirmedHeadReceiptPersistsCommentIdAndIsDuplicateOnRedelivery() {
        when(comments.create(any(), anyLong(), any())).thenReturn(new ReviewRequestStore.Receipt(555, false));
        assertEquals(COMMENT_CREATED, adapter().request(request(HEAD)));
        assertEquals("COMMENT_CREATED_HEAD_UNCONFIRMED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        assertEquals(555L, jdbc.queryForObject("SELECT comment_id FROM review_requests", Long.class));
        assertEquals(DUPLICATE, adapter().request(request(HEAD)));
    }

    @Test void lifetimeCapCountsUnknownsAndAllHeadShasAndCannotBeResetByRestart() {
        store().reserve(request(String.format("%040x", 1)), 42, 6, () -> authorization.requireAuthorized(SCOPE), () -> {});
        for (int i = 2; i <= 6; i++) {
            String sha = String.format("%040x", i);
            advanceTo(sha);
            assertEquals(COMMENT_CREATED, adapter().request(request(sha)));
        }
        advanceTo(HEAD);
        code(LIMIT_REACHED, () -> adapter().request(request(HEAD)));
        var stopped = lifecycle().find(PR).orElseThrow();
        assertEquals(PullRequestReview.State.PAUSED, stopped.state());
        assertEquals(PullRequestReview.Intervention.ITERATION_LIMIT, stopped.intervention());
        code(LIMIT_REACHED, () -> adapter().request(request(HEAD)));
        assertEquals(stopped, lifecycle().find(PR).orElseThrow());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                jdbc.queryForList("SELECT iteration FROM review_requests ORDER BY iteration", Long.class));
        assertEquals(6, rows());
        assertEquals(5, effects.get());
    }

    @Test void limitForAnUntrackedPrDeniesWithoutCreatingLifecycleState() {
        Runnable authorize = () -> authorization.requireAuthorized(SCOPE);
        assertTrue(store().reserve(request(HEAD), 42, 1, authorize, () -> {}).created());
        var next = request("b".repeat(40));
        code(LIMIT_REACHED, () -> store().reserve(next, 42, 1, authorize, () -> fail("No approval at the limit")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pr_review_states", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pr_review_transitions", Integer.class));
        assertEquals(1, rows());
    }

    @Test void limitKeepsAnExistingPauseReasonAndVersion() {
        advanceTo(HEAD);
        Runnable authorize = () -> authorization.requireAuthorized(SCOPE);
        assertTrue(store().reserve(request(HEAD), 42, 1, authorize, () -> {}).created());
        var states = new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(dataSource));
        var paused = states.locked(PR, authorize, () -> states.stopLocked(
                states.load(PR, false).orElseThrow(), PullRequestReview.Intervention.NO_PROGRESS));
        assertEquals(PullRequestReview.Intervention.NO_PROGRESS, paused.intervention());
        var other = new ReviewRequest(PR, HEAD, "DEEP", "codex", ReviewRequest.Trigger.PR_SYNCHRONIZE, 123, false);
        code(LIMIT_REACHED, () -> store().reserve(other, 42, 1, authorize, () -> fail("No approval at the limit")));
        assertEquals(paused, lifecycle().find(PR).orElseThrow());
        var human = states.locked(PR, authorize, () -> states.stopLocked(paused,
                PullRequestReview.Intervention.ITERATION_LIMIT));
        assertEquals(paused, human);
    }

    @Test void nullTransitionFailsBeforeAuthorizationOrLocking() {
        var states = new JdbcReviewStateStore(jdbc, new DataSourceTransactionManager(dataSource));
        assertThrows(NullPointerException.class, () -> states.apply(PR, null, () -> fail("Authorized a null command")));
    }

    @Test void reReviewRequiresCurrentCiBeforeReservationAndAgainBeforeSend() {
        assertEquals(COMMENT_CREATED, adapter().request(request(HEAD)));
        String next = "b".repeat(40);
        var state = lifecycle().create(PR, next);
        code(CI_NOT_PASSED, () -> adapter().request(request(next)));
        assertEquals(1, rows());
        reconcileCi(state, ReviewTransition.Ci.PASSED);
        var request = request(next);
        var reserved = store().reserve(request, 42, 6, () -> authorization.requireAuthorized(SCOPE), () -> {});
        reconcileCi(lifecycle().find(PR).orElseThrow(), ReviewTransition.Ci.PENDING);
        code(CI_NOT_PASSED, () -> store().send(request, reserved.id(), () -> authorization.requireAuthorized(SCOPE),
                () -> fail("A rerun invalidated CI before the effect")));
        assertEquals(NOT_SENT, adapter().request(request));
        assertEquals(1, effects.get());
        assertEquals(2, rows());
    }

    @Test void distinctConcurrentRequestsCompeteForOneLifetimeSlot() throws Exception {
        advanceTo(HEAD);
        Runnable authorize = () -> authorization.requireAuthorized(SCOPE);
        for (int i = 0; i < 5; i++) {
            var request = new ReviewRequest(PR, HEAD, "DEEP", "provider-" + i,
                    ReviewRequest.Trigger.PR_READY, 123, false);
            assertTrue(store().reserve(request, 42, 6, authorize, () -> {}).created());
        }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (String provider : List.of("final-a", "final-b")) {
                var request = new ReviewRequest(PR, HEAD, "DEEP", provider, ReviewRequest.Trigger.PR_READY, 123, false);
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        return store().reserve(request, 42, 6, authorize, () -> {}).created();
                    } catch (ReviewRequestFailure failure) {
                        assertEquals(LIMIT_REACHED, failure.code());
                        return false;
                    }
                }));
            }
            start.countDown();
            assertNotEquals(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
        }
        assertEquals(6, rows());
        assertEquals(PullRequestReview.Intervention.ITERATION_LIMIT, lifecycle().find(PR).orElseThrow().intervention());
    }
}
