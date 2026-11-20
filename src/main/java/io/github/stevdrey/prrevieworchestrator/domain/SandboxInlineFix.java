package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;
import java.util.UUID;

/** Evidence supplied by a trusted verifier after inspecting the two committed file revisions. */
public record SandboxInlineFix(UUID findingId, UUID snapshotId, long expectedVersion, String originalCommit,
        String correctiveCommit, String path, String originalBlob, String correctiveBlob, String verifier,
        String reason) {
    public SandboxInlineFix {
        Objects.requireNonNull(findingId);
        Objects.requireNonNull(snapshotId);
        CommitSha.require(originalCommit);
        CommitSha.require(correctiveCommit);
        CommitSha.require(originalBlob);
        CommitSha.require(correctiveBlob);
        requireText(path, 1024);
        requireText(verifier, 1024);
        requireText(reason, 4096);
        if (originalCommit.equals(correctiveCommit) || originalBlob.equals(correctiveBlob) || expectedVersion < 0) {
            throw new IllegalArgumentException("Expected a corrective commit with a changed file revision");
        }
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid inline fix evidence");
        }
    }

    @Override
    public String toString() {
        return "Sandbox inline fix for " + findingId;
    }
}
