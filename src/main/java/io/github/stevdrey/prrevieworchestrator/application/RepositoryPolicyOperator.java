package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.Snapshot;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import java.util.Objects;

/** The authenticated operator role is checked by the store inside the repository authorization transaction. */
public final class RepositoryPolicyOperator {
    private final RepositoryPolicyStore store;
    private final RepositoryAuthorization repositories;
    public RepositoryPolicyOperator(RepositoryPolicyStore store, RepositoryAuthorization repositories) {
        this.store = store;
        this.repositories = repositories;
    }

    public Snapshot read(RepositoryScope scope, long actorId) {
        return store.read(scope, actorId, () -> repositories.requireAuthorized(scope));
    }

    public Snapshot change(RepositoryScope scope, long actorId, long expectedVersion,
            Change change, RepositoryReviewPolicy policy) {
        Objects.requireNonNull(change);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || (change == Change.CONFIGURE) != (policy != null)) {
            throw new IllegalArgumentException("Invalid repository policy change");
        }
        return store.update(scope, expectedVersion, actorId, change, policy,
                () -> repositories.requireAuthorized(scope));
    }
}
