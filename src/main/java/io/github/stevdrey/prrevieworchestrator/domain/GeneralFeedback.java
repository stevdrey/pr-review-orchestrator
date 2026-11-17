package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;
import java.util.UUID;

/** Human decisions supplied by a trusted adapter, never commands parsed from comment prose. */
public final class GeneralFeedback {
    private GeneralFeedback() {}
    public enum Action { ACKNOWLEDGE, CLAIM_FIXED, DISMISS }

    public record Disposition(UUID id, UUID findingId, UUID expectedSnapshotId, String expectedHeadSha, long expectedVersion,
            Action action, String actor, String reason, String evidenceSha) {
        public Disposition {
            Objects.requireNonNull(id); Objects.requireNonNull(findingId); Objects.requireNonNull(expectedSnapshotId);
            Objects.requireNonNull(action); CommitSha.require(expectedHeadSha);
            if (expectedVersion < 0) throw new IllegalArgumentException("Invalid disposition version");
            requireText(actor, 1024); requireText(reason, 4096);
            if (action == Action.CLAIM_FIXED) CommitSha.require(evidenceSha);
            else if (evidenceSha != null) throw new IllegalArgumentException("Unexpected fix evidence");
        }
        // Human reasons may be private; do not expose them in routine diagnostics.
        @Override public String toString() { return "General feedback disposition " + id; }
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Invalid disposition metadata");
        }
    }
}
