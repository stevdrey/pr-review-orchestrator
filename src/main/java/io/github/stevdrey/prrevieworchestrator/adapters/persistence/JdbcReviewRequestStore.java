package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryReviewPolicy.Snapshot;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewRequestStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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
    private final JdbcRepositoryPolicy repositoryPolicy;
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
        this.repositoryPolicy = new JdbcRepositoryPolicy(jdbc);
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
        requireIndependentDispatch();
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
            if (!existing.isEmpty()) {
                var found = existing.getFirst();
                // A planned retry reconciles START_REVIEW from a receipt committed by an earlier attempt.
                if (request.plannedCommandId() != null && (found.state() == State.COMMENT_CREATED
                        || found.state() == State.COMMENT_CREATED_HEAD_UNCONFIRMED)) {
                    startPlannedReview(request);
                }
                return found;
            }
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
            var active = repositoryPolicy.lockCurrent(pr.scope());
            var pinned = request.plannedCommandId() == null ? active
                    : repositoryPolicy.pinned(pr.scope(), requirePlannedReview(request, current));
            requireRepositoryPolicy(request, current, pinned, active);
            if (request.plannedCommandId() == null && alreadyQueued(request)) {
                throw new ReviewRequestFailure(POLICY_DENIED);
            }
            long used = usedIterations(jdbc, pr);
            long occupied = repositoryPolicy.occupied(pr, request.plannedCommandId());
            if (occupied >= Math.min(limit, pinned.effectiveIterationCap(active))) {
                // An untracked PR has no aggregate to pause; never create one from an unverified request head.
                if (current == null) throw new ReviewRequestFailure(LIMIT_REACHED);
                // Only consumed deployment slots stop the PR; tentative outbox slots or a lowered policy cap just deny.
                if (used < limit) throw new ReviewRequestFailure(LIMIT_REACHED);
                states.stopLocked(current, Intervention.ITERATION_LIMIT);
                return null; // Commit the pause before exposing the denial.
            }
            requireEligible(request, current, used + 1);
            approve.run();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_requests (id, request_key, installation_id, repository_id, pr_number,
                        head_sha, stage, provider, actor_id, head_epoch, iteration, planned_command_id, repository_policy_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, request.key(), request.pr().scope().installationId(), request.pr().scope().repositoryId(),
                    request.pr().number(), request.headSha(), request.stage(), request.provider(), actorId,
                    request.headEpoch(), used + 1, request.plannedCommandId(), pinned.version());
            if (sandboxBudget != null) sandboxBudget.reserve(request);
            return new Reservation(id, State.RESERVED, true);
        });
        if (reservation == null) throw new ReviewRequestFailure(LIMIT_REACHED);
        return reservation;
    }
    @Override public void send(ReviewRequest request, UUID id, Runnable guards, Function<Runnable, Receipt> effect) {
        requireIndependentDispatch();
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
            // A transient planned failure leaves the rolled-back row RESERVED for the fenced worker to resume.
            if (!(failure instanceof ReviewRequestFailure known && known.code() == OUTCOME_UNKNOWN)
                    && !resumable(request, failure)) {
                markNotSent(request, id, State.RESERVED);
            }
            throw failure;
        }
        var effectReturned = new AtomicBoolean();
        try {
            transaction(() -> {
                PostgresLocks.lockPr(jdbc, request.pr());
                guards.run(); // Holds live installation membership locks throughout the external effect.
                var requestStates = jdbc.query("SELECT state FROM review_requests WHERE id = ? AND request_key = ? FOR UPDATE",
                        (rs, row) -> State.valueOf(rs.getString(1)), id, request.key());
                if (requestStates.size() != 1 || requestStates.getFirst() != State.ATTEMPTED) {
                    throw new ReviewRequestFailure(OUTCOME_UNKNOWN);
                }
                Runnable policyCheck = sendPolicyCheck(request, id);
                policyCheck.run();
                if (sandboxBudget != null) sandboxBudget.requireHeld(request);
                Receipt receipt = effect.apply(() -> {
                    guards.run();
                    policyCheck.run();
                });
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
            // The effect never returned, so a transient planned failure provably posted nothing.
            if (resumable(request, failure)) {
                restoreReserved(request, id);
            } else {
                markNotSent(request, id, State.ATTEMPTED);
            }
            throw failure;
        }
        if (request.plannedCommandId() == null) return;
        // The receipt is committed first; a failure here is reconciled by the retry's reserve().
        try {
            transaction(() -> {
                PostgresLocks.lockPr(jdbc, request.pr());
                guards.run();
                startPlannedReview(request);
                return null;
            });
        } catch (RuntimeException failure) {
            throw new ReviewRequestFailure(STORE_UNAVAILABLE);
        }
    }

    /** Only the fenced planned worker resumes a permit; standard callers keep the NOT_SENT tombstone. */
    private static boolean resumable(ReviewRequest request, RuntimeException failure) {
        if (request.plannedCommandId() == null) return false;
        if (failure instanceof AuthorizationUnavailable) return true;
        return failure instanceof ReviewRequestFailure known && switch (known.code()) {
            case REMOTE_UNAVAILABLE, STORE_UNAVAILABLE, PR_NOT_READY, CI_NOT_PASSED, CAPABILITY_UNVERIFIED,
                    QUIET_HOURS -> true;
            default -> false;
        };
    }

    private void startPlannedReview(ReviewRequest request) {
        states.load(request.pr(), false)
                .ifPresent(current -> states.reconcilePlannedStartLocked(current, request.plannedCommandId()));
    }

    /** Best effort: if this fails the row stays ATTEMPTED and a retry reports OUTCOME_UNKNOWN. */
    private void restoreReserved(ReviewRequest request, UUID id) {
        try {
            transaction(() -> jdbc.update("""
                    UPDATE review_requests SET state = 'RESERVED'
                    WHERE id = ? AND request_key = ? AND state = 'ATTEMPTED'
                    """, id, request.key()));
        } catch (RuntimeException ignored) {
            // Keep the original failure; an ATTEMPTED row is never reused as a permit.
        }
    }

    static long usedIterations(JdbcTemplate jdbc, PullRequestId pr) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(iteration), 0) FROM review_requests
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, Long.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
    }

    /** The latest START_REVIEW transition identifies the planned request that owns the active baseline. */
    static Optional<UUID> plannedBaseline(JdbcTemplate jdbc, PullRequestId pr, String headSha) {
        return jdbc.query("""
                SELECT r.id FROM (
                    SELECT transition_id FROM pr_review_transitions
                    WHERE (installation_id, repository_id, pr_number) = (?, ?, ?) AND action = 'START_REVIEW'
                    ORDER BY resulting_version DESC LIMIT 1
                ) started JOIN review_requests r ON r.id = started.transition_id
                WHERE r.planned_command_id IS NOT NULL
                    AND (r.installation_id, r.repository_id, r.pr_number, r.head_sha) = (?, ?, ?, ?)
                """, (rs, row) -> rs.getObject(1, UUID.class), pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), headSha)
                .stream().findFirst();
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

    private Runnable sendPolicyCheck(ReviewRequest request, UUID id) {
        var current = requireCurrentVisit(request);
        var reservation = jdbc.queryForMap("SELECT iteration, repository_policy_version FROM review_requests WHERE id = ?", id);
        long iteration = (Long) reservation.get("iteration");
        var scope = request.pr().scope();
        var active = repositoryPolicy.lockCurrent(scope);
        Long version = (Long) reservation.get("repository_policy_version");
        var pinned = repositoryPolicy.pinned(scope, version);
        requireEligible(request, current, iteration);
        if (request.plannedCommandId() != null && !Objects.equals(version, requirePlannedReview(request, current))) {
            throw new ReviewRequestFailure(POLICY_DENIED);
        }
        if (repositoryPolicy.occupied(request.pr(), null) > pinned.effectiveIterationCap(active)) {
            throw new ReviewRequestFailure(POLICY_DENIED);
        }
        return () -> requireRepositoryPolicy(request, current, pinned, active);
    }

    private void requireRepositoryPolicy(ReviewRequest request, PullRequestReview current, Snapshot pinned, Snapshot active) {
        // The live hold governs; work admitted during a pause runs under its pinned rules after resume.
        if (active.held()) throw new ReviewRequestFailure(HUMAN_INTERVENTION_REQUIRED);
        if (!pinned.policy().allows(request.stage(), request.provider())
                || !active.policy().allows(request.stage(), request.provider())) {
            throw new ReviewRequestFailure(POLICY_DENIED);
        }
        if ((pinned.policy().requirePassingCi() || active.policy().requirePassingCi())
                && (current == null || current.ci() != ReviewTransition.Ci.PASSED)) {
            throw new ReviewRequestFailure(CI_NOT_PASSED);
        }
        var now = repositoryPolicy.now();
        if (pinned.quiet(now) || active.quiet(now)) throw new ReviewRequestFailure(QUIET_HOURS);
    }

    private boolean alreadyQueued(ReviewRequest request) {
        var pr = request.pr();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_commands o JOIN review_cycles c ON c.id = o.review_cycle_id
                    WHERE o.command_type = 'REQUEST_REVIEW' AND o.status <> 'CANCELLED'
                        AND (o.planned_head_epoch IS NULL OR o.planned_head_epoch = ?)
                        AND (o.installation_id, o.repository_id, c.pr_number, o.expected_head_sha,
                            c.stage, o.target_provider) = (?, ?, ?, ?, ?, ?))
                """, Boolean.class, request.headEpoch(), pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                request.headSha(), request.stage(), request.provider()));
    }

    private void requireIndependentDispatch() {
        if (JdbcWorkQueue.GENERIC_DISPATCH.isBound()) {
            // The generic worker may hold the PR lock on another connection until its gateway returns.
            throw new DurableWork.Failure(DurableWork.ErrorCode.UNSUPPORTED_COMMAND, false);
        }
    }

    private void requireEligible(ReviewRequest request, PullRequestReview current, long iteration) {
        if (current != null && current.state() == PullRequestReview.State.PAUSED) {
            throw new ReviewRequestFailure(current.intervention() == null ? POLICY_DENIED : HUMAN_INTERVENTION_REQUIRED);
        }
        if (request.plannedCommandId() != null) {
            return;
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

    private Long requirePlannedReview(ReviewRequest request, PullRequestReview current) {
        if (current == null || current.state() != PullRequestReview.State.WAITING_FOR_REVIEW) {
            throw new ReviewRequestFailure(POLICY_DENIED);
        }
        if (current.ci() != ReviewTransition.Ci.PASSED) throw new ReviewRequestFailure(CI_NOT_PASSED);
        var pr = request.pr();
        var findings = JdbcGeneralFeedbackStore.findingGates(jdbc, pr, request.headSha());
        if (findings.generalNeedsDisposition() || !findings.inlinePending() && !findings.generalPending()) {
            throw new ReviewRequestFailure(POLICY_DENIED);
        }
        var versions = jdbc.query("""
                SELECT o.repository_policy_version FROM outbox_commands o JOIN review_cycles c ON c.id = o.review_cycle_id
                    WHERE o.id = ? AND o.status = 'PROCESSING' AND o.command_type = 'REQUEST_REVIEW'
                        AND o.planned_head_epoch = ? AND (o.installation_id, o.repository_id, c.pr_number,
                            o.expected_head_sha, c.stage, o.target_provider) = (?, ?, ?, ?, ?, ?)
                """, (rs, row) -> rs.getObject(1, Long.class), request.plannedCommandId(), request.headEpoch(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                request.headSha(), request.stage(), request.provider());
        if (versions.isEmpty()) throw new ReviewRequestFailure(POLICY_DENIED);
        return versions.getFirst();
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
