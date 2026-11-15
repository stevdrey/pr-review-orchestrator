package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewStateStore;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes one PR at a time; version and SHA checks still reject concurrent stale observations. */
@Repository
public class JdbcReviewStateStore implements ReviewStateStore {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReviewStateStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
    }

    private <T> T transaction(Runnable authorize, Supplier<T> operation) {
        return transactions.execute(status -> {
            try {
                PostgresDeadlines.apply(jdbc, DEADLINE);
                authorize.run();
                return operation.get();
            } catch (ReviewConflict failure) {
                throw new ReviewConflict(failure.code());
            } catch (AuthorizationDenied failure) {
                throw new AuthorizationDenied();
            } catch (AuthorizationUnavailable failure) {
                throw new AuthorizationUnavailable();
            } catch (RuntimeException failure) {
                // Sanitize before Spring rollback diagnostics, just as for durable dispatch.
                throw new IllegalStateException("REVIEW_STATE_UNAVAILABLE");
            }
        });
    }

    @Override
    public PullRequestReview create(PullRequestId pr, String headSha, Runnable authorize) {
        PullRequestReview initial = PullRequestReview.initial(pr, headSha);
        return transaction(authorize, () -> {
            int inserted = jdbc.update("""
                    INSERT INTO pr_review_states (installation_id, repository_id, pr_number, head_sha)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), headSha);
            if (inserted != 1) throw new ReviewConflict(ALREADY_EXISTS);
            return initial;
        });
    }

    @Override
    public Optional<PullRequestReview> find(PullRequestId pr, Runnable authorize) {
        return transaction(authorize, () -> load(pr, false));
    }

    private Optional<PullRequestReview> load(PullRequestId pr, boolean lock) {
        return jdbc.query("""
                SELECT * FROM pr_review_states WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new PullRequestReview(pr,
                rs.getString("head_sha"), rs.getLong("version"), State.valueOf(rs.getString("state")),
                rs.getString("resume_state") == null ? null : State.valueOf(rs.getString("resume_state")),
                rs.getString("baseline_clean_sha"), rs.getString("deep_clean_sha")),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number()).stream().findFirst();
    }

    @Override
    public PullRequestReview apply(PullRequestId pr, ReviewTransition command, Runnable authorize) {
        java.util.Objects.requireNonNull(command);
        return transaction(authorize, () -> {
            PullRequestReview current = load(pr, true).orElseThrow(() -> new ReviewConflict(NOT_FOUND));
            boolean duplicate = !jdbc.queryForList("""
                    SELECT 1 FROM pr_review_transitions WHERE installation_id = ? AND repository_id = ?
                    AND pr_number = ? AND transition_id = ?
                    """, Integer.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), command.id()).isEmpty();
            if (duplicate) throw new ReviewConflict(DUPLICATE_TRANSITION);
            PullRequestReview next = current.transition(command);
            jdbc.update("""
                    UPDATE pr_review_states SET head_sha = ?, version = ?, state = ?, resume_state = ?,
                    baseline_clean_sha = ?, deep_clean_sha = ?
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                    """, next.headSha(), next.version(), next.state().name(),
                    next.resumeState() == null ? null : next.resumeState().name(), next.baselineCleanSha(), next.deepCleanSha(),
                    pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
            jdbc.update("""
                    INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
                        expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), command.id(),
                    command.expectedVersion(), command.expectedHeadSha(), command.action().name(),
                    next.version(), next.headSha(), next.state().name());
            return next;
        });
    }
}
