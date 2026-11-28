package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

public final class RepositoryPolicyFixtures {
    private RepositoryPolicyFixtures() {}

    public static void allowReviews(JdbcTemplate jdbc, String... providers) {
        jdbc.update("""
                INSERT INTO repository_review_policy_operators (installation_id, repository_id, database_role, actor_id)
                SELECT installation_id, repository_id, session_user, 1 FROM installation_repositories
                """);
        jdbc.update("""
                INSERT INTO repository_review_policy_versions
                    (installation_id, repository_id, version, stages, providers, require_passing_ci,
                        iteration_cap, held, changed_by, change_kind)
                SELECT installation_id, repository_id, 1, ARRAY['BASELINE', 'DEEP'], ?::text[],
                    false, 6, false, 1, 'CONFIGURE'
                FROM installation_repositories
                """, (Object) providers);
    }
}
