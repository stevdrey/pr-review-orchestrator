package io.github.stevdrey.prrevieworchestrator.domain;

/** Stable identifiers, never a repository name supplied by a PR author. */
public record RepositoryScope(long installationId, long repositoryId) {
    public RepositoryScope {
        if (installationId <= 0 || repositoryId <= 0) {
            throw new IllegalArgumentException("Installation and repository IDs must be positive");
        }
    }
}
