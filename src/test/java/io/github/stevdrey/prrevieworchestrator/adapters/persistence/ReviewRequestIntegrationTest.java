package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider.Result.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.UNSUPPORTED_TRIGGER;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.CAPABILITY_UNVERIFIED;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.LIMIT_REACHED;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.STORE_UNAVAILABLE;

import io.github.stevdrey.prrevieworchestrator.adapters.github.*;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
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
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (202, 2002, true)");
        authorization = new RepositoryAuthorization(Set.of(SCOPE), new JdbcInstallationAuthorization(jdbc));
        comments = mock(GitHubCodexComments.class);
        approval = mock(CodexApproval.class);
        effects = new AtomicInteger();
        when(comments.create(any(), eq(42L), any())).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run();
            return 1000L + effects.incrementAndGet();
        });
    }
    ReviewRequest request(String head) {
        return new ReviewRequest(PR, head, "BASELINE", "codex", ReviewRequest.Trigger.PR_SYNCHRONIZE, 123, false);
    }
    JdbcReviewRequestStore store() { return new JdbcReviewRequestStore(jdbc, new DataSourceTransactionManager(dataSource)); }
    CodexReviewAdapter adapter() { return new CodexReviewAdapter(authorization, store(), approval, comments, 42, 6); }
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
                else assertTrue(result == DUPLICATE || result == OUTCOME_UNKNOWN);
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
        code(ReviewRequestFailure.Code.OUTCOME_UNKNOWN, () -> adapter().request(request(HEAD)));
        assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
        assertEquals(1, effects.get());
        assertEquals("ATTEMPTED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        var crash = request("b".repeat(40));
        store().reserve(crash, 42, 6, () -> authorization.requireAuthorized(SCOPE));
        assertEquals(OUTCOME_UNKNOWN, adapter().request(crash));
        assertEquals(1, effects.get());
    }

    @Test void permitItselfCannotBeReusedEvenAfterRollbackOfEffectTransaction() {
        var r = request(HEAD);
        var reservation = store().reserve(r, 42, 6, () -> authorization.requireAuthorized(SCOPE));
        code(ReviewRequestFailure.Code.OUTCOME_UNKNOWN, () -> store().send(r, reservation.id(), () -> authorization.requireAuthorized(SCOPE), () -> {
            effects.incrementAndGet();
            throw new ReviewRequestFailure(ReviewRequestFailure.Code.OUTCOME_UNKNOWN);
        }));
        code(ReviewRequestFailure.Code.OUTCOME_UNKNOWN, () -> store().send(r, reservation.id(), () -> authorization.requireAuthorized(SCOPE), () -> {
            effects.incrementAndGet(); return 1001;
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
        var reserved = store().reserve(r, 42, 6, () -> authorization.requireAuthorized(SCOPE));
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        assertThrows(AuthorizationDenied.class, () -> store().send(r, reserved.id(),
                () -> authorization.requireAuthorized(SCOPE), () -> { effects.incrementAndGet(); return 1; }));
        assertEquals(0, effects.get());
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
            code(STORE_UNAVAILABLE, () -> adapter().request(request(HEAD)));
            assertEquals(1, effects.get());
            assertEquals(OUTCOME_UNKNOWN, adapter().request(request(HEAD)));
            assertEquals(1, effects.get());
            assertEquals("ATTEMPTED", jdbc.queryForObject("SELECT state FROM review_requests", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER fail_review_receipt ON review_requests");
            jdbc.execute("DROP FUNCTION reject_review_receipt()");
        }
    }

    @Test void lifetimeCapCountsUnknownsAndAllHeadShasAndCannotBeResetByRestart() {
        store().reserve(request(String.format("%040x", 1)), 42, 6, () -> authorization.requireAuthorized(SCOPE));
        for (int i = 2; i <= 6; i++) assertEquals(COMMENT_CREATED, adapter().request(request(String.format("%040x", i))));
        code(LIMIT_REACHED, () -> adapter().request(request(HEAD)));
        assertEquals(6, rows());
        assertEquals(5, effects.get());
    }
}
