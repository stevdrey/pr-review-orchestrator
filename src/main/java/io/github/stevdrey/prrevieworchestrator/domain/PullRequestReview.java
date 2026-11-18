package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Gates;
import java.util.Objects;

/** Immutable aggregate. Each accepted observation changes the version, including head changes and pauses. */
public record PullRequestReview(PullRequestId pr, String headSha, long version, State state,
        State resumeState, String baselineCleanSha, String deepCleanSha, Ci ci, Intervention intervention) {
    public enum State {
        NEW, WAITING_FOR_REVIEW, REVIEWING, ACTION_REQUIRED, WAITING_FOR_FIX,
        DEEP_REVIEW, PAUSED, READY_FOR_HUMAN
    }
    public enum Intervention { ITERATION_LIMIT, NO_PROGRESS }

    public PullRequestReview {
        Objects.requireNonNull(pr);
        Objects.requireNonNull(state);
        Objects.requireNonNull(ci);
        ReviewTransition.requireSha(headSha);
        if (version < 0 || (state == State.PAUSED) != (resumeState != null) || resumeState == State.PAUSED
                || intervention != null && state != State.PAUSED) {
            throw new IllegalArgumentException("Invalid review state");
        }
        State effective = state == State.PAUSED ? resumeState : state;
        if ((baselineCleanSha != null && !headSha.equals(baselineCleanSha))
                || (deepCleanSha != null && (!headSha.equals(deepCleanSha) || baselineCleanSha == null))
                || ((effective == State.DEEP_REVIEW || effective == State.READY_FOR_HUMAN) != (baselineCleanSha != null))
                || (effective == State.READY_FOR_HUMAN && (deepCleanSha == null || ci != Ci.PASSED))) {
            throw new IllegalArgumentException("Invalid clean evidence");
        }
    }

    public static PullRequestReview initial(PullRequestId pr, String sha) {
        return new PullRequestReview(pr, sha, 0, State.NEW, null, null, null, Ci.UNKNOWN, null);
    }

    public PullRequestReview transition(ReviewTransition command) {
        if (command.expectedVersion() != version) throw new ReviewConflict(STALE_VERSION);
        if (!command.expectedHeadSha().equals(headSha)) throw new ReviewConflict(STALE_HEAD);
        State next = state;
        State resume = resumeState;
        String sha = headSha;
        String baseline = baselineCleanSha;
        String deep = deepCleanSha;
        Ci nextCi = ci;
        Intervention nextIntervention = intervention;
        Gates gates = command.gates();
        switch (command.action()) {
            case MARK_READY -> {
                requireState(State.NEW);
                guard(gates.eligible());
                next = State.WAITING_FOR_REVIEW;
            }
            case START_REVIEW -> {
                requireState(State.WAITING_FOR_REVIEW);
                guard(gates.eligible() && gates.reviewPermitted());
                next = State.REVIEWING;
            }
            case BLOCKING_FINDINGS -> {
                if (state == State.PAUSED) {
                    if (resume != State.REVIEWING && resume != State.DEEP_REVIEW && resume != State.READY_FOR_HUMAN) {
                        throw new ReviewConflict(ILLEGAL_TRANSITION);
                    }
                    resume = State.ACTION_REQUIRED;
                } else {
                    requireState(State.REVIEWING, State.DEEP_REVIEW, State.READY_FOR_HUMAN);
                    next = State.ACTION_REQUIRED;
                }
                guard(gates.blockingFindings());
                baseline = null;
                deep = null;
            }
            case AWAIT_FIX -> {
                requireState(State.ACTION_REQUIRED);
                next = State.WAITING_FOR_FIX;
            }
            case BASELINE_CLEAN -> {
                requireState(State.REVIEWING);
                guard(gates.eligible() && !gates.blockingFindings());
                next = State.DEEP_REVIEW;
                baseline = sha;
            }
            case DEEP_CLEAN -> {
                requireState(State.DEEP_REVIEW);
                if (deep != null) throw new ReviewConflict(ILLEGAL_TRANSITION);
                guard(gates.eligible() && !gates.blockingFindings());
                deep = sha;
            }
            case MARK_READY_FOR_HUMAN -> {
                requireState(State.DEEP_REVIEW);
                guard(deep != null && ready(gates));
                next = State.READY_FOR_HUMAN;
            }
            case PAUSE -> {
                if (state == State.PAUSED) throw new ReviewConflict(ILLEGAL_TRANSITION);
                resume = state;
                next = State.PAUSED;
            }
            case RESUME -> {
                requireState(State.PAUSED);
                // NEW, ACTION_REQUIRED and WAITING_FOR_FIX permit no review work, so they need no eligibility.
                guard(!reviewActive(resume) || (gates.eligible() && (resume != State.READY_FOR_HUMAN || ready(gates))));
                next = resume;
                resume = null;
                nextIntervention = null;
            }
            case UPDATE_HEAD, CONFIRM_HEAD -> {
                boolean changed = command.action() == ReviewTransition.Action.UPDATE_HEAD;
                if (changed) {
                    guard(!headSha.equals(command.newHeadSha()));
                    sha = command.newHeadSha();
                }
                // A new synchronize at the same SHA can be a return from a head we never observed.
                baseline = null;
                deep = null;
                nextCi = Ci.UNKNOWN;
                State effective = state == State.PAUSED ? resume : state;
                if (changed ? effective != State.NEW : reviewActive(effective)) {
                    if (state == State.PAUSED) resume = State.WAITING_FOR_REVIEW;
                    else next = State.WAITING_FOR_REVIEW;
                }
            }
            case RECONCILE_CI -> {
                nextCi = gates.ci();
                if (nextCi != Ci.PASSED) {
                    if (state == State.READY_FOR_HUMAN) next = State.DEEP_REVIEW;
                    if (resume == State.READY_FOR_HUMAN) resume = State.DEEP_REVIEW;
                }
            }
            case STOP_ITERATION_LIMIT, STOP_NO_PROGRESS -> {
                if (state != State.PAUSED) resume = state;
                next = State.PAUSED;
                nextIntervention = command.action() == ReviewTransition.Action.STOP_ITERATION_LIMIT
                        ? Intervention.ITERATION_LIMIT : Intervention.NO_PROGRESS;
            }
        }
        return new PullRequestReview(pr, sha, Math.incrementExact(version), next, resume, baseline, deep,
                nextCi, nextIntervention);
    }

    private void requireState(State... allowed) {
        for (State candidate : allowed) if (state == candidate) return;
        throw new ReviewConflict(ILLEGAL_TRANSITION);
    }

    private static boolean reviewActive(State state) {
        return state == State.WAITING_FOR_REVIEW || state == State.REVIEWING
                || state == State.DEEP_REVIEW || state == State.READY_FOR_HUMAN;
    }

    private boolean ready(Gates gates) {
        return ci == Ci.PASSED && gates.eligible() && !gates.blockingFindings() && gates.ci() == Ci.PASSED;
    }

    private static void guard(boolean condition) {
        if (!condition) throw new ReviewConflict(GUARD_REJECTED);
    }
}
