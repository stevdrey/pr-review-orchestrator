package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInstallationAuthorization implements InstallationAuthorization {
    private final JdbcTemplate jdbc;

    public JdbcInstallationAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveAndEnabled(RepositoryScope scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM installations i JOIN installation_repositories r
                    ON r.installation_id = i.installation_id
                    WHERE i.installation_id = ? AND r.repository_id = ?
                    AND i.status = 'ACTIVE' AND r.enabled = true)
                """, Boolean.class, scope.installationId(), scope.repositoryId()));
    }
}
