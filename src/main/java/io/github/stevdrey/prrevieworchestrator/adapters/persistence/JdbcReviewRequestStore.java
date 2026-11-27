package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
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
    private final JdbcReviewStateStore states;
    private final JdbcSandboxCodexBudget sandboxBudget;

    public JdbcReviewRequestStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, new JdbcReviewStateStore(jdbc, manager));
    }

    public JdbcReviewRequestStore(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcReviewStateStore states) {
        this(jdbc, manager, states, null);
    }

    public JdbcReviewRequestStore(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcReviewStateStore states,
            JdbcSandboxCodexBudget sandboxBudget) {
        this.jdbc = jdbc;
        this.states = states;
        this.sandboxBudget = sandboxBudget;
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
    @Override public Reservation reserve(ReviewRequest request, long actorId, int limit, Runnable authorize, Runnable approve) {
        if (actorId <= 0 || limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid review policy");
        var reservation = transaction(() -> {
            var pr = request.pr();
            authorize.run();
            PostgresLocks.lockPr(jdbc, pr);
            var existing = jdbc.query("""
                    SELECT id, state FROM review_requests WHERE request_key = ?
                    """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class),
                    State.valueOf(rs.getString("state")), false), request.key());
            // The caller authorizes duplicate reads; only a new reservation needs policy/budget approval.
            if (!existing.isEmpty()) return existing.getFirst();
            var current = requireCurrentVisit(request);
            // A V13 match preserves deduplication. Only the epoch in the original key authorizes a send.
            var reconciled = jdbc.query("""
                    SELECT id, state FROM review_requests
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND head_sha = ?
                        AND stage = ? AND provider = ? AND reconciled_head_epoch = ?
                    """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class),
                    State.valueOf(rs.getString("state")), false), request.pr().scope().installationId(),
                    request.pr().scope().repositoryId(), request.pr().number(), request.headSha(),
                    request.stage(), request.provider(), request.headEpoch());
            if (!reconciled.isEmpty()) return reconciled.getFirst();
            long used = usedIterations(jdbc, pr);
            if (used >= limit) {
                // An untracked PR has no aggregate to pause; never create one from an unverified request head.
                if (current == null) throw new ReviewRequestFailure(LIMIT_REACHED);
                states.stopLocked(current, Intervention.ITERATION_LIMIT);
                return null; // Commit the pause before exposing the denial.
            }
            requireEligible(request, current, used + 1);
            approve.run();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                        head_sha, stage, provider, actor_id, head_epoch, iteration) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, request.key(), request.pr().scope().installationId(), request.pr().scope().repositoryId(),
                    request.pr().number(), request.headSha(), request.stage(), request.provider(), actorId,
                    request.headEpoch(), used + 1);
            if (sandboxBudget != null) sandboxBudget.reserve(request);
            return new Reservation(id, State.RESERVED, true);
        });
        if (reservation == null) throw new ReviewRequestFailure(LIMIT_REACHED);
        return reservation;
    }
    @Override public void send(ReviewRequest request, UUID id, Runnable guards, Supplier<Receipt> effect) {
        try {
            transaction(() -> {
                PostgresLocks.lockPr(jdbc, request.pr());
                guards.run();
                int claimed = jdbc.update("UPDATE review_requests SET state = 'ATTEMPTED' WHERE id = ? AND request_key = ? AND state = 'RESERVED'",
                        id, request.key());
                if (claimed != 1) throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
                // Policy is enforced once, under the lock held across the effect; a denial there is still NOT_SENT.
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
                requireSendPolicy(request, id);
                if (sandboxBudget != null) sandboxBudget.requireHeld(request);
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

    static long usedIterations(JdbcTemplate jdbc, PullRequestId pr) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(iteration), 0) FROM review_requests
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, Long.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
    }

    private PullRequestReview requireCurrentVisit(ReviewRequest request) {
        var pr = request.pr();
        // Standalone callers can still use epoch 0 before the lifecycle tracks the PR.
        var current = states.load(pr, false).orElse(null);
        if (current != null && !current.headSha().equals(request.headSha())
                || request.headEpoch() != JdbcFixCorrelationStore.headEpoch(jdbc, pr, request.headSha())) {
            throw new ReviewRequestFailure(STALE_HEAD);
        }
        return current;
    }

    private void requireSendPolicy(ReviewRequest request, UUID id) {
        var current = requireCurrentVisit(request);
        long iteration = jdbc.queryForObject("SELECT iteration FROM review_requests WHERE id = ?", Long.class, id);
        requireEligible(request, current, iteration);
    }

    private void requireEligible(ReviewRequest request, PullRequestReview current, long iteration) {
        if (current != null && current.state() == PullRequestReview.State.PAUSED) {
            throw new ReviewRequestFailure(current.intervention() == null ? POLICY_DENIED : HUMAN_INTERVENTION_REQUIRED);
        }
        if (current != null && current.ci() == ReviewTransition.Ci.PASSED) return;
        var pr = request.pr();
        boolean reReview = request.headEpoch() != 0 || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM review_requests
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND stage = ? AND iteration < ?)
                OR EXISTS (SELECT 1 FROM pr_review_transitions
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                    AND action IN ('BLOCKING_FINDINGS', 'AWAIT_FIX'))
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), request.stage(),
                iteration, pr.scope().installationId(), pr.scope().repositoryId(), pr.number()));
        if (reReview) throw new ReviewRequestFailure(CI_NOT_PASSED);
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
