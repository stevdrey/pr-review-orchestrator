package io.github.stevdrey.prrevieworchestrator.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trusted, metadata-only inputs to remediation re-review eligibility. */
public final class FixCorrelation {
    private FixCorrelation() {}

    public record Head(PullRequestId pr, String sha, boolean open, boolean draft) {
        public Head {
            Objects.requireNonNull(pr);
            CommitSha.require(sha);
        }
    }

    /** Authoritative comparison of committed trees, including across non-linear history. */
    public record Comparison(String baseSha, String headSha, boolean changed) {
        public Comparison {
            CommitSha.require(baseSha);
            CommitSha.require(headSha);
        }
    }

    /** The caller captures the version before the authoritative head and comparison reads. */
    public record Assessment(Head head, long expectedVersion, boolean humanHold, Comparison comparison) {
        public Assessment {
            Objects.requireNonNull(head);
            if (expectedVersion < 0) throw new IllegalArgumentException("Invalid review state version");
        }
    }

    public enum Status {
        NO_REMEDIATION, NO_NEW_CODE, NOT_REVIEWABLE, WAITING_FOR_PUSHES, COMPARISON_REQUIRED,
        GENERAL_DISPOSITION_REQUIRED, NO_PENDING_FINDINGS, ALREADY_REQUESTED, ELIGIBLE, ALREADY_ELIGIBLE
    }

    /** Eligibility carries the current request epoch. A candidate ID is never a permit to dispatch. */
    public record Decision(Status status, String baseSha, Instant retryAt, UUID candidateId, long headEpoch) {
        public Decision(Status status, String baseSha, Instant retryAt, UUID candidateId) {
            this(status, baseSha, retryAt, candidateId, 0);
        }

        public Decision(Status status) {
            this(status, null, null, null);
        }
    }
}
