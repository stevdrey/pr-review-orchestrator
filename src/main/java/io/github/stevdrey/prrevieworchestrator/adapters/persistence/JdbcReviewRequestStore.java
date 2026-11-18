package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
            PostgresLocks.lockPr(jdbc, request.pr());
            var existing = jdbc.query("""
                    SELECT id, state FROM review_requests WHERE request_key = ?
                    """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class),
                    State.valueOf(rs.getString("state")), false), request.key());
            // The caller authorizes duplicate reads; only a new reservation needs policy/budget approval.
            if (!existing.isEmpty()) return existing.getFirst();
            requireCurrentEpoch(request);
            guards.run();
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM review_requests WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                    """, Integer.class, request.pr().scope().installationId(), request.pr().scope().repositoryId(), request.pr().number());
            if (count == null || count >= limit) throw new ReviewRequestFailure(LIMIT_REACHED);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                        head_sha, stage, provider, actor_id, head_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, request.key(), request.pr().scope().installationId(), request.pr().scope().repositoryId(),
                    request.pr().number(), request.headSha(), request.stage(), request.provider(), actorId,
                    request.headEpoch());
            return new Reservation(id, State.RESERVED, true);
        });
    }
    @Override public void send(ReviewRequest request, UUID id, Runnable guards, Supplier<Receipt> effect) {
        try {
            transaction(() -> {
                PostgresLocks.lockPr(jdbc, request.pr());
                guards.run();
                int claimed = jdbc.update("UPDATE review_requests SET state = 'ATTEMPTED' WHERE id = ? AND request_key = ? AND state = 'RESERVED'",
                        id, request.key());
                if (claimed != 1) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
                requireCurrentEpoch(request);
                return null;
            });
        } catch (RuntimeException failure) {
            // Another holder may own a non-RESERVED row; only our own still-RESERVED row is provably unsent.
            if (!(failure instanceof ReviewRequestFailure known && known.code() == OUTCOME_UNKNOWN)) {
                markNotSent(request, id, State.RESERVED);
            }
            throw failure;
        }
        var effectReturned = new AtomicBoolean();
        try {
            transaction(() -> {
                PostgresLocks.lockPr(jdbc, request.pr());
                guards.run(); // Holds live installation membership locks throughout the external effect.
                var states = jdbc.query("SELECT state FROM review_requests WHERE id = ? AND request_key = ? FOR UPDATE",
                        (rs, row) -> State.valueOf(rs.getString(1)), id, request.key());
                if (states.size() != 1 || states.getFirst() != State.ATTEMPTED) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
                requireCurrentEpoch(request);
                Receipt receipt = effect.get();
                effectReturned.set(true);
                if (receipt == null || receipt.commentId() <= 0) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
                jdbc.update("UPDATE review_requests SET state = ?, comment_id = ?, completed_at = clock_timestamp() WHERE id = ?",
                        (receipt.headConfirmed() ? State.COMMENT_CREATED : State.COMMENT_CREATED_HEAD_UNCONFIRMED).name(),
                        receipt.commentId(), id);
                return null;
            });
        } catch (RuntimeException failure) {
            // After the effect returned, a statement or COMMIT failure may hide a created comment.
            if (effectReturned.get() || failure instanceof ReviewRequestFailure known && known.code() == OUTCOME_UNKNOWN) {
                throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
            }
            markNotSent(request, id, State.ATTEMPTED);
            throw failure;
        }
    }

    private void requireCurrentEpoch(ReviewRequest request) {
        if (request.headEpoch() != JdbcFixCorrelationStore.headEpoch(jdbc, request.pr(), request.headSha())) {
            throw new ReviewRequestFailure(STALE_HEAD);
        }
    }

    /** Best effort: if this fails the row keeps its conservative RESERVED/ATTEMPTED state. */
    private void markNotSent(ReviewRequest request, UUID id, State expected) {
        try {
            transaction(() -> jdbc.update("""
                    UPDATE review_requests SET state = 'NOT_SENT', completed_at = clock_timestamp()
                    WHERE id = ? AND request_key = ? AND state = ?
                    """, id, request.key(), expected.name()));
        } catch (RuntimeException ignored) {
            // Keep the original actionable failure; never weaken the tombstone.
        }
    }
}
