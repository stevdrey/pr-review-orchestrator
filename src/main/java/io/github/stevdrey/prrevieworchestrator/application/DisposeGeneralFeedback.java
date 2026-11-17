package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.GeneralFeedback.Disposition;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.ports.GeneralFeedbackStore;

/** The caller must authenticate the actor as the PR author or an authorized maintainer. */
public final class DisposeGeneralFeedback {
    private final RepositoryAuthorization authorization;
    private final GeneralFeedbackStore store;

    public DisposeGeneralFeedback(RepositoryAuthorization authorization, GeneralFeedbackStore store) {
        this.authorization = authorization;
        this.store = store;
    }

    public boolean dispose(PullRequestId pr, Disposition disposition) {
        return store.dispose(pr, disposition, () -> authorization.requireAuthorized(pr.scope()));
    }
}
