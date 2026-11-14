package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import java.util.Set;

/** An explicit deployment allowlist AND live enabled membership are required on every use. */
public final class RepositoryAuthorization {
    private final Set<RepositoryScope> allowed;
    private final InstallationAuthorization installations;

    public RepositoryAuthorization(Set<RepositoryScope> allowed, InstallationAuthorization installations) {
        this.allowed = Set.copyOf(allowed);
        this.installations = java.util.Objects.requireNonNull(installations);
    }

    public void requireAuthorized(RepositoryScope scope) {
        try {
            if (scope != null && allowed.contains(scope) && installations.isActiveAndEnabled(scope)) {
                return;
            }
        } catch (RuntimeException ignored) {
            // Do not propagate SQL/driver diagnostics or fail open when the authority is unavailable.
        }
        throw new SecurityException("Repository authorization denied");
    }
}
