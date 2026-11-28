package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.Snapshot;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;

public interface RepositoryPolicyStore {
    enum Change { CONFIGURE, PAUSE, RESUME }

    Snapshot read(RepositoryScope scope, long actorId, Runnable authorize);

    /** Compare and append atomically. CONFIGURE preserves the current hold. */
    Snapshot update(RepositoryScope scope, long expectedVersion, long actorId, Change change,
            RepositoryReviewPolicy policy, Runnable authorize);
}
