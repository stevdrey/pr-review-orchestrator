package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import java.util.OptionalLong;
import java.util.UUID;

public interface ReviewOutcomeStore {
    /** Resolve the exact committed GitHub receipt before consulting an authenticated source. */
    OptionalLong codexTrigger(ReviewOutcome.Run run, Runnable authorize);
    /** Authorize and validate durable request/cycle/current evidence under the PR lock. */
    ReviewOutcome assess(ReviewOutcome.Assessment assessment, Runnable authorize);
    /** Reassess and atomically journal a clean-stage transition; nonclean results cannot advance. */
    PullRequestReview recordClean(ReviewOutcome.Assessment assessment, UUID commandId,
            ReviewTransition.Gates gates, Runnable authorize);
}
