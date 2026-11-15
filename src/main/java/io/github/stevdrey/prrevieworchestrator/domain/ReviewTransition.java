package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;
import java.util.UUID;

/** Trusted application observations/decisions for expectedHeadSha, never raw webhook or model output. */
public record ReviewTransition(UUID id, long expectedVersion, String expectedHeadSha, Action action,
        String newHeadSha, Gates gates) {
    public enum Action {
        MARK_READY, START_REVIEW, BLOCKING_FINDINGS, AWAIT_FIX, BASELINE_CLEAN,
        DEEP_CLEAN, MARK_READY_FOR_HUMAN, PAUSE, RESUME, UPDATE_HEAD
    }
    public enum Ci { UNKNOWN, PENDING, FAILED, PASSED }

    /** Policy/budget decisions are inputs, not an implementation of the future policy ledger. */
    public record Gates(boolean open, boolean draft, boolean humanHold, boolean reviewPermitted,
            boolean blockingFindings, Ci ci) {
        public Gates { Objects.requireNonNull(ci); }
        public boolean eligible() { return open && !draft && !humanHold; }
    }

    public ReviewTransition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(action);
        Objects.requireNonNull(gates);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid state version");
        }
        requireSha(expectedHeadSha);
        if (action == Action.UPDATE_HEAD) requireSha(newHeadSha);
        else if (newHeadSha != null) throw new IllegalArgumentException("Unexpected head update");
    }

    public static void requireSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid head SHA");
        }
    }
}
