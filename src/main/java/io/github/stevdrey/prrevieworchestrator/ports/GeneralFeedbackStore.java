package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback.Disposition;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;

public interface GeneralFeedbackStore {
    /** Atomic audit and projection; false only for an exact, already committed retry. */
    boolean dispose(PullRequestId pr, Disposition disposition, Runnable authorize);
}
