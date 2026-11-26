package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict.Code.*;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.Intervention;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewStateStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
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
        // Every operation sets transaction_timeout; fail startup rather than every transaction.
        PostgresDeadlines.requireTransactionTimeout(jdbc, "review state");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
    }

    private <T> T transaction(Runnable authorize, Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, DEADLINE);
                    authorize.run();
                    return operation.get();
                } catch (RuntimeException failure) {
                    // Sanitize before Spring rollback diagnostics, just as for durable dispatch.
                    throw sanitized(failure);
                }
            });
        } catch (RuntimeException failure) {
            // COMMIT-time failures (deferred constraints, timeouts, connection loss) surface here.
            throw sanitized(failure);
        }
    }

    /** Copy only stable metadata, never causes, SQL text or suppressed exceptions. */
    private static RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof ReviewConflict conflict) return new ReviewConflict(conflict.code());
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        if (failure instanceof ReviewStateUnavailable known) return new ReviewStateUnavailable(known.retryable());
        // Domain validation inside a transaction only fails for a corrupt persisted snapshot.
        if (failure instanceof IllegalArgumentException) return new ReviewStateUnavailable(false);
        return new ReviewStateUnavailable(true);
    }

    @Override
    public PullRequestReview create(PullRequestId pr, String headSha, Runnable authorize) {
        return locked(pr, authorize, () -> createLocked(pr, headSha));
    }

    PullRequestReview createLocked(PullRequestId pr, String headSha) {
        PullRequestReview initial = PullRequestReview.initial(pr, headSha);
        int inserted = jdbc.update("""
                INSERT INTO pr_review_states (installation_id, repository_id, pr_number, head_sha)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), headSha);
        if (inserted != 1) throw new ReviewConflict(ALREADY_EXISTS);
        return initial;
    }

    @Override
    public Optional<PullRequestReview> find(PullRequestId pr, Runnable authorize) {
        return transaction(authorize, () -> load(pr, false));
    }

    Optional<PullRequestReview> load(PullRequestId pr, boolean lock) {
        return jdbc.query("""
                SELECT * FROM pr_review_states WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new PullRequestReview(pr,
                rs.getString("head_sha"), rs.getLong("version"), State.valueOf(rs.getString("state")),
                rs.getString("resume_state") == null ? null : State.valueOf(rs.getString("resume_state")),
                rs.getString("baseline_clean_sha"), rs.getString("deep_clean_sha"), Ci.valueOf(rs.getString("ci_status")),
                rs.getString("intervention") == null ? null : Intervention.valueOf(rs.getString("intervention"))),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number()).stream().findFirst();
    }

    @Override
    public PullRequestReview apply(PullRequestId pr, ReviewTransition command, Runnable authorize) {
        java.util.Objects.requireNonNull(command);
        return locked(pr, authorize, () -> switch (command.action()) {
            case RECONCILE_CI, STOP_ITERATION_LIMIT, STOP_NO_PROGRESS -> throw new ReviewConflict(GUARD_REJECTED);
            default -> applyLocked(pr, command, null);
        });
    }

    @Override
    public PullRequestReview reconcileCi(long expectedVersion, CiEvidence evidence, Runnable authorize) {
        return locked(evidence.pr(), authorize, () -> reconcileCiLocked(expectedVersion, evidence));
    }

    PullRequestReview reconcileCiLocked(long expectedVersion, CiEvidence evidence) {
        var command = new ReviewTransition(evidence.id(), expectedVersion, evidence.headSha(),
                Action.RECONCILE_CI, null, observationGates(evidence.status()));
        return applyLocked(evidence.pr(), command, null);
    }

    PullRequestReview stopLocked(PullRequestReview current, Intervention intervention) {
        return stopLocked(current, intervention, UUID.randomUUID());
    }

    PullRequestReview stopLocked(PullRequestReview current, Intervention intervention, UUID transitionId) {
        // An existing hold (human or automatic) keeps its reason and version until a human resumes it.
        if (current.state() == State.PAUSED) return current;
        var action = intervention == Intervention.ITERATION_LIMIT ? Action.STOP_ITERATION_LIMIT : Action.STOP_NO_PROGRESS;
        return applyLocked(current.pr(), new ReviewTransition(transitionId, current.version(), current.headSha(),
                action, null, observationGates(Ci.UNKNOWN)), null);
    }

    private static ReviewTransition.Gates observationGates(Ci ci) {
        return new ReviewTransition.Gates(false, false, false, false, false, ci);
    }

    /** Shared bounded, authorized, PR-serialized transaction with sanitized failures for persistence adapters. */
    <T> T locked(PullRequestId pr, Runnable authorize, Supplier<T> operation) {
        return transaction(authorize, () -> {
            PostgresLocks.lockPr(jdbc, pr);
            return operation.get();
        });
    }

    /**
     * Caller holds the PR lock and authorized transaction; validation runs before any write.
     * A supplied evidence guard must prove no pending findings; it replaces the default pending-findings query.
     */
    PullRequestReview applyLocked(PullRequestId pr, ReviewTransition command, Runnable evidenceGuard) {
        PullRequestReview current = load(pr, true).orElseThrow(() -> new ReviewConflict(NOT_FOUND));
        String resultingHead = command.action() == ReviewTransition.Action.UPDATE_HEAD
                ? command.newHeadSha() : command.expectedHeadSha();
        var replays = jdbc.query("""
                SELECT expected_version = ? AND expected_head_sha = ? AND action = ? AND resulting_head_sha = ?
                    AND ci_status IS NOT DISTINCT FROM CAST(? AS text)
                FROM pr_review_transitions WHERE installation_id = ? AND repository_id = ?
                AND pr_number = ? AND transition_id = ?
                """, (rs, row) -> rs.getBoolean(1), command.expectedVersion(), command.expectedHeadSha(),
                command.action().name(), resultingHead, command.action() == Action.RECONCILE_CI ? command.gates().ci().name() : null,
                pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), command.id());
        // A UUID reused for a different command must not be mistaken for an already-committed retry.
        if (!replays.isEmpty()) throw new ReviewConflict(replays.getFirst() ? DUPLICATE_TRANSITION : TRANSITION_ID_REUSED);
        PullRequestReview next = current.transition(command);
        // After domain validation so stale/illegal commands keep their own conflict codes.
        boolean ready = next.state() == State.READY_FOR_HUMAN
                && (command.action() == Action.MARK_READY_FOR_HUMAN || command.action() == Action.RESUME);
        if (evidenceGuard != null) {
            evidenceGuard.run();
        } else if ((command.action() == ReviewTransition.Action.BASELINE_CLEAN
                || command.action() == ReviewTransition.Action.DEEP_CLEAN
                || ready)
                && JdbcGeneralFeedbackStore.blocking(jdbc, pr, current.headSha())) {
            throw new ReviewConflict(GUARD_REJECTED);
        }
        jdbc.update("""
                UPDATE pr_review_states SET head_sha = ?, version = ?, state = ?, resume_state = ?,
                baseline_clean_sha = ?, deep_clean_sha = ?, ci_status = ?, intervention = ?
                WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                """, next.headSha(), next.version(), next.state().name(),
                next.resumeState() == null ? null : next.resumeState().name(), next.baselineCleanSha(), next.deepCleanSha(),
                next.ci().name(), next.intervention() == null ? null : next.intervention().name(),
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
        jdbc.update("""
                INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
                    expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state, ci_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(), command.id(),
                command.expectedVersion(), command.expectedHeadSha(), command.action().name(),
                next.version(), next.headSha(), next.state().name(), command.action() == Action.RECONCILE_CI ? next.ci().name() : null);
        return next;
    }
}
