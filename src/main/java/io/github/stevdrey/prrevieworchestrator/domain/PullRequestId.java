package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;

/** Provider-independent PR identity scoped to an authorized installation/repository pair. */
public record PullRequestId(RepositoryScope scope, int number) {
    public PullRequestId {
        Objects.requireNonNull(scope);
        if (number <= 0) throw new IllegalArgumentException("Invalid PR number");
    }
}
