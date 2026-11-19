package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PullRequestReviewTest {
    static final String HEAD = "a".repeat(40);
    static final String NEXT = "b".repeat(40);
    static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    static final Gates CLEAR = new Gates(true, false, false, true, false, Ci.PASSED);
    static final Gates BLOCKED = new Gates(true, false, false, true, true, Ci.PASSED);

    static ReviewTransition command(PullRequestReview state, Action action, Gates gates) {
        return new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action,
                action == UPDATE_HEAD ? NEXT : null, gates);
    }

    static PullRequestReview step(PullRequestReview state, Action action) {
        return state.transition(command(state, action, action == BLOCKING_FINDINGS ? BLOCKED : CLEAR));
    }

    static PullRequestReview at(State state) {
        State effective = state == PAUSED ? REVIEWING : state;
        String baseline = effective == DEEP_REVIEW || effective == READY_FOR_HUMAN ? HEAD : null;
        return new PullRequestReview(PR, HEAD, 3, state, state == PAUSED ? REVIEWING : null,
                baseline, effective == READY_FOR_HUMAN ? HEAD : null, Ci.PASSED, null);
    }

    @Test
    void fullCycleRequiresFreshReviewsAfterFixAndExplicitReadyGate() {
        var state = PullRequestReview.initial(PR, HEAD);
        state = step(state, MARK_READY);
        state = step(state, START_REVIEW);
        state = step(state, BLOCKING_FINDINGS);
        state = step(state, AWAIT_FIX);
        state = step(state, UPDATE_HEAD);
        assertEquals(WAITING_FOR_REVIEW, state.state());
        state = step(state, START_REVIEW);
        state = step(state, BASELINE_CLEAN);
        assertEquals(NEXT, state.baselineCleanSha());
        state = step(state, DEEP_CLEAN);
        assertEquals(DEEP_REVIEW, state.state());
        rejected(state, MARK_READY_FOR_HUMAN, CLEAR);
        state = step(state, RECONCILE_CI);
        state = step(state, MARK_READY_FOR_HUMAN);
        assertEquals(READY_FOR_HUMAN, state.state());
        assertEquals(NEXT, state.deepCleanSha());
        assertEquals(10, state.version());
    }

    @ParameterizedTest
    @EnumSource(State.class)
    void graphRejectsEveryIllegalStateActionPair(State source) {
        Map<State, Set<Action>> allowed = Map.of(
                NEW, Set.of(MARK_READY, PAUSE, UPDATE_HEAD),
                WAITING_FOR_REVIEW, Set.of(START_REVIEW, PAUSE, UPDATE_HEAD),
                REVIEWING, Set.of(BASELINE_CLEAN, BLOCKING_FINDINGS, PAUSE, UPDATE_HEAD),
                ACTION_REQUIRED, Set.of(AWAIT_FIX, PAUSE, UPDATE_HEAD),
                WAITING_FOR_FIX, Set.of(PAUSE, UPDATE_HEAD),
                DEEP_REVIEW, Set.of(DEEP_CLEAN, MARK_READY_FOR_HUMAN, BLOCKING_FINDINGS, PAUSE, UPDATE_HEAD),
                READY_FOR_HUMAN, Set.of(BLOCKING_FINDINGS, PAUSE, UPDATE_HEAD),
                PAUSED, Set.of(RESUME, UPDATE_HEAD, BLOCKING_FINDINGS));
        for (Action action : Action.values()) {
            var state = at(source);
            if (source == DEEP_REVIEW && action == MARK_READY_FOR_HUMAN) state = step(state, DEEP_CLEAN);
            var request = command(state, action, action == BLOCKING_FINDINGS ? BLOCKED : CLEAR);
            var current = state;
            if (action == CONFIRM_HEAD || action == RECONCILE_CI || action == STOP_ITERATION_LIMIT
                    || action == STOP_NO_PROGRESS || allowed.get(source).contains(action)) {
                assertEquals(current.version() + 1, current.transition(request).version(), source + "/" + action);
            } else {
                assertEquals(ILLEGAL_TRANSITION, assertThrows(ReviewConflict.class,
                        () -> current.transition(request), source + "/" + action).code());
            }
        }
    }

    @Test
    void feedbackWhilePausedPreservesPauseAndRevokesBothCleanStages() {
        var state = step(at(READY_FOR_HUMAN), PAUSE);
        state = step(state, BLOCKING_FINDINGS);
        assertEquals(PAUSED, state.state());
        assertEquals(ACTION_REQUIRED, state.resumeState());
        assertNull(state.baselineCleanSha());
        assertNull(state.deepCleanSha());
        assertEquals(ACTION_REQUIRED, step(state, RESUME).state());
    }

    @ParameterizedTest
    @EnumSource(State.class)
    void headChangesInvalidateEvidenceAndPreservePause(State source) {
        var state = step(at(source), UPDATE_HEAD);
        assertNull(state.baselineCleanSha());
        assertNull(state.deepCleanSha());
        assertEquals(NEXT, state.headSha());
        if (source == PAUSED) {
            assertEquals(PAUSED, state.state());
            assertEquals(WAITING_FOR_REVIEW, step(state, RESUME).state());
        } else assertEquals(source == NEW ? NEW : WAITING_FOR_REVIEW, state.state());
    }

    @Test
    void headChangeNeverBypassesMarkReadyEligibility() {
        var draft = new Gates(true, true, false, true, false, Ci.PASSED);
        var fresh = PullRequestReview.initial(PR, HEAD);
        assertEquals(NEW, fresh.transition(command(fresh, UPDATE_HEAD, draft)).state());
        var paused = step(fresh, PAUSE);
        var moved = paused.transition(command(paused, UPDATE_HEAD, draft));
        assertEquals(PAUSED, moved.state());
        assertEquals(NEW, moved.resumeState());
    }

    @Test
    void resumeToStatesWithoutReviewWorkDoesNotRequireEligibility() {
        var draft = new Gates(true, true, false, true, false, Ci.PASSED);
        var held = new Gates(true, false, true, true, false, Ci.PASSED);
        for (State source : List.of(NEW, ACTION_REQUIRED, WAITING_FOR_FIX)) {
            for (Gates gates : List.of(draft, held)) {
                var paused = step(at(source), PAUSE);
                assertEquals(source, paused.transition(command(paused, RESUME, gates)).state());
            }
        }
    }

    @Test
    void returningToPreviouslyCleanShaStillRequiresNewReviews() {
        var state = step(at(READY_FOR_HUMAN), UPDATE_HEAD);
        state = state.transition(new ReviewTransition(UUID.randomUUID(), state.version(), NEXT, UPDATE_HEAD, HEAD, CLEAR));
        assertEquals(HEAD, state.headSha());
        assertNull(state.baselineCleanSha());
        assertNull(state.deepCleanSha());
        assertEquals(WAITING_FOR_REVIEW, state.state());
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = "PAUSED", mode = EnumSource.Mode.EXCLUDE)
    void pauseResumeRestoresExactStateAndEvidence(State source) {
        var before = at(source);
        var paused = step(before, PAUSE);
        assertEquals(source, paused.resumeState());
        var resumed = step(paused, RESUME);
        assertEquals(source, resumed.state());
        assertNull(resumed.resumeState());
        assertEquals(before.baselineCleanSha(), resumed.baselineCleanSha());
        assertEquals(before.deepCleanSha(), resumed.deepCleanSha());
    }

    @Test
    void guardsFailClosedForEligibilityPolicyFindingsAndCi() {
        var ineligible = List.of(
                new Gates(false, false, false, true, false, Ci.PASSED),
                new Gates(true, true, false, true, false, Ci.PASSED),
                new Gates(true, false, true, true, false, Ci.PASSED));
        for (Gates gates : ineligible) {
            rejected(at(NEW), MARK_READY, gates);
            rejected(at(WAITING_FOR_REVIEW), START_REVIEW, gates);
            rejected(at(PAUSED), RESUME, gates);
            rejected(at(REVIEWING), BASELINE_CLEAN, gates);
            rejected(at(DEEP_REVIEW), DEEP_CLEAN, gates);
            rejected(step(at(DEEP_REVIEW), DEEP_CLEAN), MARK_READY_FOR_HUMAN, gates);
        }
        rejected(at(WAITING_FOR_REVIEW), START_REVIEW, new Gates(true, false, false, false, false, Ci.PASSED));
        rejected(at(REVIEWING), BASELINE_CLEAN, BLOCKED);
        rejected(at(DEEP_REVIEW), DEEP_CLEAN, BLOCKED);
        rejected(at(REVIEWING), BLOCKING_FINDINGS, CLEAR);
        rejected(at(DEEP_REVIEW), MARK_READY_FOR_HUMAN, CLEAR);
        for (Ci ci : List.of(Ci.UNKNOWN, Ci.PENDING, Ci.FAILED)) {
            // Only the reconciled CI on the aggregate counts; a caller-supplied passing gate cannot substitute.
            var unreconciled = new PullRequestReview(PR, HEAD, 3, DEEP_REVIEW, null, HEAD, HEAD, ci, null);
            rejected(unreconciled, MARK_READY_FOR_HUMAN, CLEAR);
        }
        rejected(step(at(DEEP_REVIEW), DEEP_CLEAN), MARK_READY_FOR_HUMAN, BLOCKED);
    }

    @Test
    void duplicateSemanticActionsAndStaleObservationsCannotAdvance() {
        var before = at(REVIEWING);
        var request = command(before, BASELINE_CLEAN, CLEAR);
        var after = before.transition(request);
        assertEquals(STALE_VERSION, assertThrows(ReviewConflict.class, () -> after.transition(request)).code());
        assertEquals(ILLEGAL_TRANSITION, assertThrows(ReviewConflict.class, () -> step(after, BASELINE_CLEAN)).code());
        var clean = step(after, DEEP_CLEAN);
        assertEquals(ILLEGAL_TRANSITION, assertThrows(ReviewConflict.class, () -> step(clean, DEEP_CLEAN)).code());
        var staleSha = new ReviewTransition(UUID.randomUUID(), after.version(), NEXT, DEEP_CLEAN, null, CLEAR);
        assertEquals(STALE_HEAD, assertThrows(ReviewConflict.class, () -> after.transition(staleSha)).code());
        rejected(before, UPDATE_HEAD, CLEAR, HEAD);
    }

    @Test
    void concurrentVersionedUpdatesHaveOnlyOneWinner() throws Exception {
        var initial = at(REVIEWING);
        var ref = new AtomicReference<>(initial);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Action action : List.of(BASELINE_CLEAN, PAUSE)) {
                var request = command(initial, action, CLEAR);
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        ref.updateAndGet(current -> current.transition(request));
                        return true;
                    } catch (ReviewConflict conflict) {
                        assertEquals(STALE_VERSION, conflict.code());
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(initial.version() + 1, ref.get().version());
        }
    }

    @Test
    void invalidIdentityAndInconsistentSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PullRequestId(PR.scope(), 0));
        assertThrows(IllegalArgumentException.class, () -> PullRequestReview.initial(PR, "main"));
        assertThrows(IllegalArgumentException.class, () ->
                new PullRequestReview(PR, HEAD, 0, READY_FOR_HUMAN, null, HEAD, HEAD, Ci.UNKNOWN, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PullRequestReview(PR, HEAD, 0, PAUSED, PAUSED, null, null, Ci.UNKNOWN, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PullRequestReview(PR, HEAD, 0, DEEP_REVIEW, null, NEXT, null, Ci.UNKNOWN, null));
        assertEquals("c".repeat(64), PullRequestReview.initial(PR, "c".repeat(64)).headSha());
    }

    static void rejected(PullRequestReview state, Action action, Gates gates) {
        rejected(state, action, gates, action == UPDATE_HEAD ? NEXT : null);
    }

    static void rejected(PullRequestReview state, Action action, Gates gates, String newHead) {
        var request = new ReviewTransition(UUID.randomUUID(), state.version(), state.headSha(), action, newHead, gates);
        assertEquals(GUARD_REJECTED, assertThrows(ReviewConflict.class, () -> state.transition(request)).code());
    }
}
