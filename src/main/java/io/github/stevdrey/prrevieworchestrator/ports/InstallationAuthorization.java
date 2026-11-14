package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;

/** Live operator-managed installation status and repository membership; failures must deny access. */
@FunctionalInterface
public interface InstallationAuthorization {
    boolean isActiveAndEnabled(RepositoryScope scope);
}
