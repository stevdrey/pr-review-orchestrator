package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.REVIEW_ASSESSMENT_EVENT;
import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.REQUEST_REVIEW;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL clocks, SKIP LOCKED claims and per-claim fencing. No JVM clock orders work. */
public final class JdbcWorkQueue implements WorkQueue {
    /** Every queue transaction, including any gateway call inside executeCommand, finishes within this. */
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    // Three independent 8s request transactions, including the send, plus bounded cleanup and queue overhead.
    private static final Duration PLANNED_REVIEW_DEADLINE = Duration.ofSeconds(40);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate plannedReviews;
    private final Limits limits;
    private final JdbcRepositoryPolicy repositoryPolicy;
    private final String worker = UUID.randomUUID().toString();

    public JdbcWorkQueue(JdbcTemplate jdbc, PlatformTransactionManager manager, Limits limits) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.repositoryPolicy = new JdbcRepositoryPolicy(jdbc);
        transactions = transactions(manager, DEADLINE);
        plannedReviews = transactions(manager, PLANNED_REVIEW_DEADLINE);
    }

    private TransactionTemplate transactions(PlatformTransactionManager manager, Duration deadline) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(Math.toIntExact(deadline.toSeconds()));
        return template;
    }

    private <T> T transaction(Supplier<T> operation) {
        return transaction(transactions, DEADLINE, operation);
    }

    private <T> T transaction(TransactionTemplate template, Duration deadline, Supplier<T> operation) {
        return template.execute(status -> {
            try {
                PostgresDeadlines.apply(jdbc, deadline);
                jdbc.execute("SET LOCAL statement_timeout = '" + deadline.minusSeconds(1).toMillis() + "ms'");
                return operation.get();
            } catch (RuntimeException failure) {
                // Spring may log the callback exception during rollback (including rollback failure).
                // Normalize before handing control back to TransactionTemplate, without a raw cause.
                throw Failure.sanitized(failure);
            }
        });
    }

    @Override
    public Optional<Event> claimEvent() {
        return transaction(() -> {
            var lease = claim(Queue.INBOX, "");
            if (lease.isEmpty()) return Optional.empty();
            return Optional.of(jdbc.queryForObject("SELECT * FROM inbox_events WHERE id = ?", (rs, row) -> {
                Long repository = rs.getObject("repository_id", Long.class);
                return new Event(lease.get(), repository == null ? null
                        : new RepositoryScope(rs.getLong("installation_id"), repository),
                        rs.getString("event_type"), rs.getString("event_action"),
                        rs.getObject("pr_number", Integer.class), rs.getString("head_sha"));
            }, lease.get().id()));
        });
    }

    @Override
    public Optional<Command> claimCommand() {
        return claimCommand(" AND planned_head_epoch IS NULL");
    }

    @Override
    public Optional<Command> claimPlannedReview(PullRequestId pr) {
        return claimCommand("""
                AND planned_head_epoch IS NOT NULL AND (installation_id, repository_id) = (?, ?)
                AND EXISTS (SELECT 1 FROM review_cycles c WHERE c.id = outbox_commands.review_cycle_id
                    AND c.pr_number = ?)
                """, pr.scope().installationId(), pr.scope().repositoryId(), pr.number());
    }

    private Optional<Command> claimCommand(String filter, Object... args) {
        return transaction(() -> {
            var lease = claim(Queue.OUTBOX, filter + """
                     AND (command_type <> 'REQUEST_REVIEW' OR NOT coalesce((
                         SELECT held FROM repository_review_policy_versions policy
                         WHERE (policy.installation_id, policy.repository_id) =
                             (outbox_commands.installation_id, outbox_commands.repository_id)
                         ORDER BY version DESC LIMIT 1), false))
                    """, args);
            if (lease.isEmpty()) return Optional.empty();
            try {
                Command command = jdbc.queryForObject("""
                        SELECT o.*, c.pr_number, c.stage FROM outbox_commands o
                        JOIN review_cycles c ON c.id = o.review_cycle_id WHERE o.id = ?
                        """, (rs, row) -> new Command(lease.get(),
                        new RepositoryScope(rs.getLong("installation_id"), rs.getLong("repository_id")),
                        new Intent(rs.getInt("pr_number"), rs.getString("expected_head_sha"),
                                rs.getString("stage"), rs.getString("target_provider"), rs.getString("command_type")),
                        rs.getString("command_key"), rs.getObject("planned_head_epoch", Long.class)), lease.get().id());
                if (!command.intent().key(command.scope()).equals(command.key())) {
                    throw new IllegalArgumentException("Invalid command identity");
                }
                return Optional.of(command);
            } catch (IllegalArgumentException poison) {
                finish(lease.get(), "DEAD", ErrorCode.INVALID_EVENT);
                return Optional.empty();
            }
        });
    }

    private Optional<Lease> claim(Queue queue, String filter, Object... args) {
        String table = table(queue);
        // Bounded recovery, including crashes during the final allowed attempt. Never reset attempts.
        var exhausted = jdbc.query("SELECT id, correlation_id, attempts FROM " + table + """
                 WHERE attempts >= max_attempts AND (status = 'PENDING' OR
                    (status = 'PROCESSING' AND locked_until <= clock_timestamp()))
                """ + filter + """
                 ORDER BY available_at, id LIMIT 32 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Lease(queue, rs.getObject("id", UUID.class), null,
                rs.getObject("correlation_id", UUID.class), rs.getInt("attempts")), args);
        for (Lease lease : exhausted) finish(lease, "DEAD", ErrorCode.LEASE_EXHAUSTED);
        var candidates = jdbc.query("SELECT id, correlation_id, attempts FROM " + table + """
                 WHERE attempts < max_attempts AND ((status = 'PENDING' AND available_at <= clock_timestamp())
                    OR (status = 'PROCESSING' AND locked_until <= clock_timestamp()))
                """ + filter + """
                 ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Lease(queue, rs.getObject("id", UUID.class), UUID.randomUUID(),
                rs.getObject("correlation_id", UUID.class), rs.getInt("attempts") + 1), args);
        if (candidates.isEmpty()) return Optional.empty();
        Lease lease = candidates.getFirst();
        jdbc.update("UPDATE " + table + """
                 SET status = 'PROCESSING', attempts = attempts + 1, locked_by = ?, lease_token = ?,
                     locked_until = clock_timestamp() + (? * interval '1 millisecond') WHERE id = ?
                """, worker, lease.token(), limits.lease().toMillis(), lease.id());
        journal(lease, "CLAIMED", null);
        return Optional.of(lease);
    }

    @Override
    public boolean completeEvent(Event event, List<Intent> intents, Runnable authorize) {
        if (intents.size() > MAX_INTENTS) throw new Failure(ErrorCode.INVALID_EVENT, false);
        return completeLocalEvent(event, authorize, () -> {
            var reviewIntents = intents.stream().filter(intent -> REQUEST_REVIEW.equals(intent.type())).toList();
            // Acquire all PR locks before the shared policy lock, in stable order across event batches.
            reviewIntents.stream().mapToInt(Intent::prNumber).distinct().sorted()
                    .forEach(number -> PostgresLocks.lockPr(jdbc, new PullRequestId(event.scope(), number)));
            var snapshot = reviewIntents.isEmpty() ? null : repositoryPolicy.lockCurrent(event.scope());
            for (Intent intent : intents) {
                if (!REQUEST_REVIEW.equals(intent.type())) {
                    enqueue(event, intent, null, null);
                    continue;
                }
                var pr = new PullRequestId(event.scope(), intent.prNumber());
                if (snapshot.permits(intent.stage(), intent.provider(), repositoryPolicy.now())
                        && repositoryPolicy.occupied(pr, null) < snapshot.policy().iterationCap()
                        && reviewStatePermits(pr, intent, snapshot.policy().requirePassingCi())
                        && !alreadyReserved(pr, intent)) {
                    enqueue(event, intent, null, snapshot.version());
                }
            }
        });
    }

    /** The local write shares the inbox claim transaction. It must use the same transaction manager. */
    boolean completeLocalEvent(Event event, Runnable authorize, Runnable localWrite) {
        return withEvent(event, authorize, () -> {
            localWrite.run();
            finishEvent(event);
            return true;
        }).orElse(false);
    }

    <T> Optional<T> withEvent(Event event, Runnable authorize, Supplier<T> operation) {
        return transaction(() -> {
            if (!lock(event.lease())) return Optional.empty();
            authorize.run();
            return Optional.of(operation.get());
        });
    }

    void finishEvent(Event event) {
        finish(event.lease(), "PROCESSED", null);
    }

    /**
     * Schedule at most one assessment per PR head and quiet-period deadline in the fenced completion
     * transaction. The synchronize and CI wake-ups in the same quiet period share it; the first keeps its correlation.
     */
    void scheduleReviewAssessment(Event event, PullRequestId pr, String headSha, Instant availableAt) {
        // Ingress only accepts UUID delivery IDs. An internal row has no event_action, so the
        // signed payload fingerprint continues to deduplicate only the original delivery.
        String key = "review-assessment:" + pr.scope().installationId() + ":" + pr.scope().repositoryId() + ":"
                + pr.number() + ":" + headSha + ":" + availableAt;
        jdbc.update("""
                INSERT INTO inbox_events (id, delivery_id, event_type, installation_id, repository_id,
                    pr_number, head_sha, payload_sha256, correlation_id, verified_at, max_attempts, available_at)
                SELECT ?, ?, ?, installation_id, repository_id,
                    ?, ?, payload_sha256, correlation_id, verified_at, max_attempts, ?
                FROM inbox_events WHERE id = ? AND (installation_id, repository_id) = (?, ?)
                ON CONFLICT (delivery_id) DO NOTHING
                """, UUID.randomUUID(), key, REVIEW_ASSESSMENT_EVENT, pr.number(), headSha, Timestamp.from(availableAt),
                event.lease().id(), pr.scope().installationId(), pr.scope().repositoryId());
    }

    void enqueue(Event event, Intent intent, Long plannedHeadEpoch, Long policyVersion) {
        var scope = event.scope();
        UUID cycle = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT
                    (installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration) DO NOTHING
                """, cycle, scope.installationId(), scope.repositoryId(), intent.prNumber(),
                intent.expectedHeadSha(), intent.stage(), intent.provider());
        cycle = jdbc.queryForObject("""
                SELECT id FROM review_cycles WHERE installation_id = ? AND repository_id = ? AND pr_number = ?
                AND head_sha = ? AND stage = ? AND review_provider = ? AND iteration = 1
                """, UUID.class, scope.installationId(), scope.repositoryId(), intent.prNumber(),
                intent.expectedHeadSha(), intent.stage(), intent.provider());
        jdbc.update("""
                INSERT INTO outbox_commands (id, command_key, review_cycle_id, installation_id, repository_id,
                    expected_head_sha, command_type, target_provider, correlation_id, planned_head_epoch, repository_policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (command_key) DO NOTHING
                """, UUID.randomUUID(), intent.key(scope), cycle, scope.installationId(), scope.repositoryId(),
                intent.expectedHeadSha(), intent.type(), intent.provider(), event.lease().correlationId(), plannedHeadEpoch,
                policyVersion);
    }

    @Override
    public boolean executeCommand(Command command, Runnable authorize, Function<BooleanSupplier, Outcome> operation) {
        if (command.plannedHeadEpoch() != null) throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
        return transaction(() -> {
            if (!lock(command.lease())) return false;
            authorize.run();
            // Holding the row lock prevents another worker stealing the lease during a bounded API call.
            Outcome outcome = java.util.Objects.requireNonNull(operation.apply(() -> reviewPolicyAllows(command)));
            finishCommand(command.lease(), outcome);
            return true;
        });
    }

    private boolean reviewPolicyAllows(Command command) {
        var intent = command.intent();
        if (!REQUEST_REVIEW.equals(intent.type())) return true;
        var pr = new PullRequestId(command.scope(), intent.prNumber());
        PostgresLocks.lockPr(jdbc, pr);
        var active = repositoryPolicy.lockCurrent(command.scope());
        Long version = jdbc.queryForObject("SELECT repository_policy_version FROM outbox_commands WHERE id = ?",
                Long.class, command.lease().id());
        var pinned = repositoryPolicy.pinned(command.scope(), version);
        var now = repositoryPolicy.now();
        return pinned.permits(intent.stage(), intent.provider(), now)
                && active.permits(intent.stage(), intent.provider(), now)
                && repositoryPolicy.occupied(pr, null) <= active.policy().iterationCap()
                && reviewStatePermits(pr, intent, pinned.policy().requirePassingCi() || active.policy().requirePassingCi());
    }

    private boolean reviewStatePermits(PullRequestId pr, Intent intent, boolean requireCi) {
        return jdbc.query("""
                SELECT head_sha = ? AND state <> 'PAUSED' AND (NOT ? OR ci_status = 'PASSED')
                FROM pr_review_states WHERE (installation_id, repository_id, pr_number) = (?, ?, ?)
                """, (rs, row) -> rs.getBoolean(1), intent.expectedHeadSha(), requireCi,
                pr.scope().installationId(), pr.scope().repositoryId(), pr.number()).stream().findFirst().orElse(!requireCi);
    }

    private boolean alreadyReserved(PullRequestId pr, Intent intent) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM review_requests
                    WHERE (installation_id, repository_id, pr_number, head_sha, stage, provider) = (?, ?, ?, ?, ?, ?))
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                intent.expectedHeadSha(), intent.stage(), intent.provider()));
    }

    @Override
    public boolean executePlannedReview(Command command, Supplier<Outcome> operation) {
        if (command.plannedHeadEpoch() == null) throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
        return transaction(plannedReviews, PLANNED_REVIEW_DEADLINE, () -> {
            // Allow the request's foreign-key check to take KEY SHARE without releasing the claim fence.
            if (!lock(command.lease(), "FOR NO KEY UPDATE")) return false;
            // Independent request transactions own the authorization and PR locks for the POST.
            finishCommand(command.lease(), java.util.Objects.requireNonNull(operation.get()));
            return true;
        });
    }

    private void finishCommand(Lease lease, Outcome outcome) {
        if (outcome == Outcome.POLICY_DENIED) finish(lease, Outcome.CANCELLED.name(), ErrorCode.POLICY_DENIED);
        else finish(lease, outcome.name(), null);
    }

    @Override
    public boolean fail(Lease lease, ErrorCode code, boolean retryable) {
        return transaction(() -> {
            if (!lock(lease)) return false;
            Integer max = jdbc.queryForObject("SELECT max_attempts FROM " + table(lease.queue()) + " WHERE id = ?",
                    Integer.class, lease.id());
            if (!retryable || lease.attempt() >= max) {
                finish(lease, "DEAD", code);
            } else {
                jdbc.update("UPDATE " + table(lease.queue()) + """
                         SET status = 'PENDING', locked_by = NULL, locked_until = NULL, lease_token = NULL,
                             last_error_code = ?, available_at = clock_timestamp() + (? * interval '1 millisecond')
                         WHERE id = ?
                        """, code.name(), limits.backoff(lease.attempt()).toMillis(), lease.id());
                journal(lease, "RETRY", code);
            }
            return true;
        });
    }

    private boolean lock(Lease lease) {
        return lock(lease, "FOR UPDATE");
    }

    private boolean lock(Lease lease, String lockClause) {
        if (lease.token() == null) return false;
        boolean acquired = !jdbc.queryForList("SELECT id FROM " + table(lease.queue()) + """
                 WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                 AND locked_until > clock_timestamp()
                """ + lockClause, UUID.class, lease.id(), lease.token()).isEmpty();
        // A predicate can be evaluated before waiting for a row lock. Recheck the DB clock after
        // acquiring it so a rolled-back competing transaction cannot let an expired owner proceed.
        return acquired && Boolean.TRUE.equals(jdbc.queryForObject("SELECT locked_until > clock_timestamp() FROM "
                + table(lease.queue()) + " WHERE id = ?", Boolean.class, lease.id()));
    }

    private void finish(Lease lease, String status, ErrorCode code) {
        String timestamp = lease.queue() == Queue.INBOX ? "processed_at" : "completed_at";
        jdbc.update("UPDATE " + table(lease.queue()) + " SET status = ?, " + timestamp + """
                 = clock_timestamp(), locked_by = NULL, locked_until = NULL, lease_token = NULL,
                 last_error_code = ? WHERE id = ?
                """, status, code == null ? null : code.name(), lease.id());
        journal(lease, status, code);
    }

    private void journal(Lease lease, String outcome, ErrorCode code) {
        jdbc.update("""
                INSERT INTO queue_transitions (queue, work_id, correlation_id, attempt, outcome, error_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, lease.queue().name(), lease.id(), lease.correlationId(), lease.attempt(),
                outcome, code == null ? null : code.name());
    }

    private static String table(Queue queue) {
        return switch (queue) { case INBOX -> "inbox_events"; case OUTBOX -> "outbox_commands"; };
    }
}
