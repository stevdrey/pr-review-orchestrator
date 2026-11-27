package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import io.github.stevdrey.prrevieworchestrator.ports.CodexCompletionSource;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/** Internal boundary. Only an approved adapter may supply authenticated completion metadata. */
public final class AssessReviewOutcome {
    private final ReviewOutcomeStore store;
    private final RepositoryAuthorization authorization;
    private final Optional<CodexCompletionSource> codex;

    public AssessReviewOutcome(ReviewOutcomeStore store, RepositoryAuthorization authorization) {
        this(store, authorization, Optional.empty());
    }

    public AssessReviewOutcome(ReviewOutcomeStore store, RepositoryAuthorization authorization,
            Optional<CodexCompletionSource> codex) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
        this.codex = Objects.requireNonNull(codex);
    }

    /** The version is captured with the evidence read, never refreshed to make delayed evidence pass.
     * An authenticated completion updates progress evidence. Repeated actionable findings can request human intervention.
     */
    public ReviewOutcome assess(Assessment assessment) {
        requireDirectAssessment(assessment);
        return store.assess(assessment, authorize(assessment.run()));
    }

    /** Unknown/ambiguous/actionable verdicts cannot create a clean-stage transition. */
    public PullRequestReview recordClean(Assessment assessment, UUID commandId, ReviewTransition.Gates gates) {
        requireDirectAssessment(assessment);
        return store.recordClean(assessment, commandId, gates, authorize(assessment.run()));
    }

    public ReviewOutcome assessCodex(Run run, long expectedVersion, UUID snapshotId) {
        return store.assess(codexAssessment(run, expectedVersion, snapshotId), authorize(run));
    }

    public PullRequestReview recordCodexClean(Run run, long expectedVersion, UUID snapshotId,
            UUID commandId, ReviewTransition.Gates gates) {
        return store.recordClean(codexAssessment(run, expectedVersion, snapshotId), commandId, gates, authorize(run));
    }

    private Assessment codexAssessment(Run run, long expectedVersion, UUID snapshotId) {
        if (!"codex".equals(run.provider()) || !"github".equals(run.evidenceProvider())) {
            throw new IllegalArgumentException("Codex completion requires GitHub evidence");
        }
        var missing = new Assessment(run, expectedVersion, null, snapshotId);
        if (codex.isEmpty() || snapshotId == null) return missing;
        var trigger = store.codexTrigger(run, authorize(run));
        if (trigger.isEmpty()) return missing;
        Optional<CodexCompletionSource.TerminalAttempt> result;
        try {
            result = codex.orElseThrow().terminalAttempt(run.pr(), trigger.getAsLong());
        } catch (RuntimeException unavailable) {
            // Keep provider diagnostics out of the outcome and still report retained findings.
            return missing;
        }
        return result.filter(attempt -> {
            var proof = attempt.provenance();
            return run.pr().equals(proof.pr()) && trigger.getAsLong() == proof.commentId()
                    && run.stage() == proof.stage() && run.headSha().equals(proof.reviewedSha())
                    && run.reviewer().equals(attempt.reviewer());
        }).map(attempt -> new Assessment(run, expectedVersion,
                new Completion(run, attempt.reviewId(), attempt.status(), attempt.conclusion(), attempt.provenance()),
                snapshotId)).orElse(missing);
    }

    private void requireDirectAssessment(Assessment assessment) {
        if (assessment.completion() != null && assessment.completion().codexProvenance() != null) {
            throw new IllegalArgumentException("Codex provenance must come from the configured source");
        }
    }

    private Runnable authorize(Run run) {
        return () -> authorization.requireAuthorized(run.pr().scope());
    }
}
