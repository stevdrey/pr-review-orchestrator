package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.QuietHours;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.Snapshot;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Stage;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore;
import io.github.stevdrey.prrevieworchestrator.ports.RepositoryPolicyStore.Change;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Policy editors take the exclusive repository lock. Review decisions hold its shared counterpart through commit. */
public final class JdbcRepositoryPolicy {
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final Snapshot DEFAULT = new Snapshot(0, RepositoryReviewPolicy.DEFAULT, false);
    private final JdbcTemplate jdbc;

    public JdbcRepositoryPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RepositoryPolicyStore operatorStore(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
        return new RepositoryPolicyStore() {
            @Override public Snapshot read(RepositoryScope scope, long actorId, Runnable authorize) {
                return transaction.execute(status -> {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    return operatorSnapshot(scope, actorId, false);
                });
            }

            @Override public Snapshot update(RepositoryScope scope, long expectedVersion, long actorId, Change change,
                    RepositoryReviewPolicy policy, Runnable authorize) {
                return transaction.execute(status -> {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    var previous = operatorSnapshot(scope, actorId, true);
                    if (previous.version() != expectedVersion) throw new ReviewConflict(ReviewConflict.Code.STALE_VERSION);
                    return append(scope, actorId, change, policy, previous);
                });
            }
        };
    }

    private Snapshot operatorSnapshot(RepositoryScope scope, long actorId, boolean editing) {
        try {
            return jdbc.query("SELECT * FROM read_repository_review_policy(?, ?, ?, ?)", this::snapshot,
                    scope.installationId(), scope.repositoryId(), actorId, editing).stream().findFirst().orElse(DEFAULT);
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql && "42501".equals(sql.getSQLState())) {
                throw new AuthorizationDenied();
            }
            throw failure;
        }
    }

    private Snapshot append(RepositoryScope scope, long actorId, Change change, RepositoryReviewPolicy replacement,
            Snapshot previous) {
        var policy = change == Change.CONFIGURE ? replacement : previous.policy();
        boolean held = switch (change) {
            case CONFIGURE -> previous.held();
            case PAUSE -> true;
            case RESUME -> false;
        };
        long version = previous.version() + 1;
        QuietHours quiet = policy.quietHours();
        jdbc.query("SELECT append_repository_review_policy(?, ?, ?, ?::text[], ?::text[], ?, ?, ?, ?, ?, ?, ?, ?)",
                rs -> {}, scope.installationId(), scope.repositoryId(), version,
                policy.stages().stream().map(Stage::name).sorted().toArray(String[]::new),
                policy.providers().stream().sorted().toArray(String[]::new), policy.requirePassingCi(),
                quiet == null ? null : quiet.zone().getId(), quiet == null ? null : quiet.startMinute(),
                quiet == null ? null : quiet.endMinute(), policy.iterationCap(), held, actorId, change.name());
        return new Snapshot(version, policy, held);
    }

    Snapshot lockCurrent(RepositoryScope scope) {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", rs -> {},
                "review-policy:" + scope.installationId() + ":" + scope.repositoryId());
        return jdbc.query("""
                SELECT * FROM repository_review_policy_versions WHERE installation_id = ? AND repository_id = ?
                ORDER BY version DESC LIMIT 1
                """, this::snapshot, scope.installationId(), scope.repositoryId()).stream().findFirst().orElse(DEFAULT);
    }

    Snapshot pinned(RepositoryScope scope, Long version) {
        if (version == null) return DEFAULT;
        return jdbc.queryForObject("""
                SELECT * FROM repository_review_policy_versions WHERE installation_id = ? AND repository_id = ? AND version = ?
                """, this::snapshot, scope.installationId(), scope.repositoryId(), version);
    }

    private Snapshot snapshot(ResultSet rs, int row) throws SQLException {
        var stages = Arrays.stream((String[]) rs.getArray("stages").getArray()).map(Stage::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        var providers = Arrays.stream((String[]) rs.getArray("providers").getArray())
                .collect(Collectors.toUnmodifiableSet());
        String zone = rs.getString("quiet_zone");
        QuietHours quiet = null;
        if (zone != null) {
            try {
                quiet = new QuietHours(ZoneId.of(zone), rs.getInt("quiet_start_minute"), rs.getInt("quiet_end_minute"));
            } catch (DateTimeException | IllegalArgumentException unknownZone) {
                // Fail closed without wedging reads: the denying default keeps the stored hold so CONFIGURE can replace it.
                return new Snapshot(rs.getLong("version"), RepositoryReviewPolicy.DEFAULT, rs.getBoolean("held"));
            }
        }
        var policy = new RepositoryReviewPolicy(stages, providers, rs.getBoolean("require_passing_ci"), quiet,
                rs.getInt("iteration_cap"));
        return new Snapshot(rs.getLong("version"), policy, rs.getBoolean("held"));
    }

    /** The latest end of any quiet window in either snapshot that currently contains {@code now}, or null. */
    static Instant quietUntil(Snapshot pinned, Snapshot active, Instant now) {
        Instant until = null;
        for (var snapshot : new Snapshot[] {pinned, active}) {
            if (!snapshot.quiet(now)) continue;
            var end = snapshot.policy().quietHours().end(now);
            if (until == null || end.isAfter(until)) until = end;
        }
        return until;
    }

    Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** Count an outbox action until a request for the same target already consumes an iteration. */
    long occupied(PullRequestId pr, UUID excludedCommand) {
        long planned = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_commands o JOIN review_cycles c ON c.id = o.review_cycle_id
                WHERE (c.installation_id, c.repository_id, c.pr_number) = (?, ?, ?)
                  AND o.command_type = 'REQUEST_REVIEW' AND o.status <> 'CANCELLED'
                  AND o.id IS DISTINCT FROM ?::uuid
                  AND NOT EXISTS (SELECT 1 FROM review_requests r WHERE
                    (r.installation_id, r.repository_id, r.pr_number, r.head_sha, r.stage, r.provider) =
                    (c.installation_id, c.repository_id, c.pr_number, o.expected_head_sha, c.stage, o.target_provider))
                """, Long.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), excludedCommand);
        return JdbcReviewRequestStore.usedIterations(jdbc, pr) + planned;
    }

}
