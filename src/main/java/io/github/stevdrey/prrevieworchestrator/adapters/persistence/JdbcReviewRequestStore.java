package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.*;

/** Permanent reservation, one-shot send permit, then an authorized bounded send.
 * Never delete reservations/redrive unknown effects: an absent comment search cannot prove absence.
 */
public final class JdbcReviewRequestStore implements ReviewRequestStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcReviewRequestStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        PostgresDeadlines.requireTransactionTimeout(jdbc, "review requests");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(8);
    }
    private <T> T transaction(Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, Duration.ofSeconds(8));
                    return operation.get();
                } catch (RuntimeException failure) { throw ReviewRequestFailure.sanitized(failure); }
            });
        } catch (RuntimeException failure) { throw ReviewRequestFailure.sanitized(failure); }
    }
    @Override public Reservation reserve(ReviewRequest request, long actorId, int limit, Runnable guards) {
        if (actorId <= 0 || limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid review policy");
        return transaction(() -> {
            JdbcGeneralFeedbackStore.lockPr(jdbc, request.pr());
            var existing = jdbc.query("""
                    SELECT id, state FROM review_requests WHERE request_key = ?
                    """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class),
                    State.valueOf(rs.getString("state")), false), request.key());
            // The caller authorizes duplicate reads; only a new reservation needs policy/budget approval.
            if (!existing.isEmpty()) return existing.getFirst();
            guards.run();
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM review_requests WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                    """, Integer.class, request.pr().scope().installationId(), request.pr().scope().repositoryId(), request.pr().number());
            if (count == null || count >= limit) throw new ReviewRequestFailure(LIMIT_REACHED);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                        head_sha, stage, provider, actor_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, request.key(), request.pr().scope().installationId(), request.pr().scope().repositoryId(),
                    request.pr().number(), request.headSha(), request.stage(), request.provider(), actorId);
            return new Reservation(id, State.RESERVED, true);
        });
    }
    @Override public void send(ReviewRequest request, UUID id, Runnable guards, LongSupplier effect) {
        transaction(() -> {
            guards.run();
            int claimed = jdbc.update("UPDATE review_requests SET state = 'ATTEMPTED' WHERE id = ? AND request_key = ? AND state = 'RESERVED'",
                    id, request.key());
            if (claimed != 1) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            return null;
        });
        transaction(() -> {
            guards.run(); // Holds live installation membership locks throughout the external effect.
            var states = jdbc.query("SELECT state FROM review_requests WHERE id = ? AND request_key = ? FOR UPDATE",
                    (rs, row) -> State.valueOf(rs.getString(1)), id, request.key());
            if (states.size() != 1 || states.getFirst() != State.ATTEMPTED) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            long commentId = effect.getAsLong();
            if (commentId <= 0) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            jdbc.update("UPDATE review_requests SET state = 'COMMENT_CREATED', comment_id = ?, completed_at = clock_timestamp() WHERE id = ?",
                    commentId, id);
            return null;
        });
    }
}
