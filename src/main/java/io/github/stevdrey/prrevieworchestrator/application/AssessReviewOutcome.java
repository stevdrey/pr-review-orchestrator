package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewOutcomeStore;
import java.util.Objects;
import java.util.UUID;

/** Internal boundary. Only an approved adapter may supply authenticated completion metadata. */
public final class AssessReviewOutcome {
    private final ReviewOutcomeStore store;
    private final RepositoryAuthorization authorization;

    public AssessReviewOutcome(ReviewOutcomeStore store, RepositoryAuthorization authorization) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
    }

    /** The version is captured with the evidence read, never refreshed to make delayed evidence pass.
     * A correlated actionable completion can persist progress evidence and request human intervention.
     */
    public ReviewOutcome assess(Assessment assessment) {
        return store.assess(assessment, () -> authorization.requireAuthorized(assessment.run().pr().scope()));
    }

    /** Unknown/ambiguous/actionable verdicts cannot create a clean-stage transition. */
    public PullRequestReview recordClean(Assessment assessment, UUID commandId, ReviewTransition.Gates gates) {
        return store.recordClean(assessment, commandId, gates,
                () -> authorization.requireAuthorized(assessment.run().pr().scope()));
    }
}
