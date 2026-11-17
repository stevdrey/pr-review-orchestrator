package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared transaction-scoped advisory locks for every PostgreSQL adapter that serializes per PR. */
public final class PostgresLocks {
    private PostgresLocks() {}

    /** Serializes ingestion, lifecycle, feedback and review-request writes, even before a PR aggregate exists. */
    public static void lockPr(JdbcTemplate jdbc, PullRequestId pr) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {},
                "review-pr:" + pr.scope().installationId() + ":" + pr.scope().repositoryId() + ":" + pr.number());
    }
}
