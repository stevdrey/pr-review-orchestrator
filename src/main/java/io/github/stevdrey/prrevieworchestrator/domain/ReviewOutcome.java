package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;
import java.util.UUID;

/** A derived verdict, never an approval to merge or invoke a provider. */
public final class ReviewOutcome {
    public enum Verdict { CLEAN_FOR_SHA, ACTION_REQUIRED, NEEDS_HUMAN, UNKNOWN }
    public enum Stage {
        BASELINE, DEEP;

        /** The only lifecycle action a clean verdict for this stage may journal. */
        public ReviewTransition.Action cleanAction() {
            return this == BASELINE ? ReviewTransition.Action.BASELINE_CLEAN : ReviewTransition.Action.DEEP_CLEAN;
        }
    }
    public enum Status { COMPLETED, PENDING, FAILED, TIMED_OUT, UNKNOWN }
    public enum Conclusion { NO_FINDINGS, FINDINGS, UNSTRUCTURED, UNKNOWN }
    public enum PendingFindings { CLEAR, ACTIONABLE, NEEDS_HUMAN, UNKNOWN }
    public enum Reason {
        CORRELATED_NO_FINDINGS, BLOCKING_FINDINGS, AMBIGUOUS_FEEDBACK, MISSING_COMPLETION,
        PROVIDER_NOT_COMPLETED, UNCORRELATED_COMPLETION, INCOMPLETE_EVIDENCE,
        MISSING_REVIEW, UNVERIFIED_REVIEW, UNKNOWN_CONCLUSION, CONTRADICTORY_EVIDENCE, NO_PROGRESS
    }

    /** Established by a trusted caller from the durable request and cycle, not echoed provider prose. */
    public record Run(PullRequestId pr, String headSha, String provider, Stage stage,
            UUID requestId, UUID cycleId, String evidenceProvider, String reviewer) {
        public Run {
            Objects.requireNonNull(pr); CommitSha.require(headSha); Objects.requireNonNull(stage);
            Objects.requireNonNull(requestId); Objects.requireNonNull(cycleId);
            for (String value : new String[] {provider, evidenceProvider, reviewer}) {
                if (value == null || value.isBlank() || value.length() > 1024) {
                    throw new IllegalArgumentException("Invalid review run");
                }
            }
        }
    }

    /**
     * Only an approved adapter with an independently verified completion contract may supply
     * NO_FINDINGS. A comment receipt, approval state or textual LGTM is UNSTRUCTURED/UNKNOWN.
     */
    public record Completion(Run run, String reviewId, Status status, Conclusion conclusion) {
        public Completion {
            Objects.requireNonNull(run); Objects.requireNonNull(status); Objects.requireNonNull(conclusion);
            if (reviewId != null && (reviewId.isBlank() || reviewId.length() > 1024)) {
                throw new IllegalArgumentException("Invalid completion reference");
            }
        }
    }

    /** References to immutable persisted evidence; missing evidence is represented explicitly. */
    public record Assessment(Run run, long expectedVersion, Completion completion, UUID snapshotId) {
        public Assessment {
            Objects.requireNonNull(run);
            if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid state version");
        }
    }

    private final Run run;
    private final long version;
    private final Verdict verdict;
    private final Reason reason;

    // Only the deterministic classifier can produce accepted outcomes in this package.
    ReviewOutcome(Run run, long version, Verdict verdict, Reason reason) {
        this.run = run;
        this.version = version;
        this.verdict = verdict;
        this.reason = reason;
    }

    public Run run() { return run; }
    public long version() { return version; }
    public Verdict verdict() { return verdict; }
    public Reason reason() { return reason; }

    public ReviewOutcome noProgress(long pausedVersion) {
        return new ReviewOutcome(run, pausedVersion, Verdict.NEEDS_HUMAN, Reason.NO_PROGRESS);
    }

    /**
     * Version-bound command for domain policy tests only. Production clean transitions go through
     * ReviewOutcomeStore.recordClean, which reclassifies and journals evidence in the same transaction.
     */
    ReviewTransition cleanTransition(UUID commandId, ReviewTransition.Gates gates) {
        if (verdict != Verdict.CLEAN_FOR_SHA) {
            throw new ReviewConflict(ReviewConflict.Code.GUARD_REJECTED);
        }
        return new ReviewTransition(commandId, version, run.headSha(), run.stage().cleanAction(), null, gates);
    }
}
