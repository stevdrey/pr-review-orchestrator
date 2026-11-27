package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.REVIEW_ASSESSMENT_EVENT;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL clocks, SKIP LOCKED claims and per-claim fencing. No JVM clock orders work. */
public final class JdbcWorkQueue implements WorkQueue {
    /** Every queue transaction, including any gateway call inside executeCommand, finishes within this. */
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Limits limits;
    private final String worker = UUID.randomUUID().toString();

    public JdbcWorkQueue(JdbcTemplate jdbc, PlatformTransactionManager manager, Limits limits) {
        this.jdbc = jdbc;
        this.limits = limits;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(DEADLINE.toSeconds()));
    }

    private <T> T transaction(Supplier<T> operation) {
        return transactions.execute(status -> {
            try {
                PostgresDeadlines.apply(jdbc, DEADLINE);
                jdbc.execute("SET LOCAL statement_timeout = '" + DEADLINE.minusSeconds(1).toMillis() + "ms'");
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
            var lease = claim(Queue.INBOX);
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
        return transaction(() -> {
            var lease = claim(Queue.OUTBOX);
            if (lease.isEmpty()) return Optional.empty();
            try {
                Command command = jdbc.queryForObject("""
                        SELECT o.*, c.pr_number, c.stage FROM outbox_commands o
                        JOIN review_cycles c ON c.id = o.review_cycle_id WHERE o.id = ?
                        """, (rs, row) -> new Command(lease.get(),
                        new RepositoryScope(rs.getLong("installation_id"), rs.getLong("repository_id")),
                        new Intent(rs.getInt("pr_number"), rs.getString("expected_head_sha"),
                                rs.getString("stage"), rs.getString("target_provider"), rs.getString("command_type")),
                        rs.getString("command_key")), lease.get().id());
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

    private Optional<Lease> claim(Queue queue) {
        String table = table(queue);
        // Bounded recovery, including crashes during the final allowed attempt. Never reset attempts.
        var exhausted = jdbc.query("SELECT id, correlation_id, attempts FROM " + table + """
                 WHERE attempts >= max_attempts AND (status = 'PENDING' OR
                    (status = 'PROCESSING' AND locked_until <= clock_timestamp()))
                 ORDER BY available_at, id LIMIT 32 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Lease(queue, rs.getObject("id", UUID.class), null,
                rs.getObject("correlation_id", UUID.class), rs.getInt("attempts")));
        for (Lease lease : exhausted) finish(lease, "DEAD", ErrorCode.LEASE_EXHAUSTED);
        var candidates = jdbc.query("SELECT id, correlation_id, attempts FROM " + table + """
                 WHERE attempts < max_attempts AND ((status = 'PENDING' AND available_at <= clock_timestamp())
                    OR (status = 'PROCESSING' AND locked_until <= clock_timestamp()))
                 ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Lease(queue, rs.getObject("id", UUID.class), UUID.randomUUID(),
                rs.getObject("correlation_id", UUID.class), rs.getInt("attempts") + 1));
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
            for (Intent intent : intents) enqueue(event, intent);
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

    /** Schedule at most one assessment per source delivery in its fenced completion transaction. */
    void scheduleReviewAssessment(Event event, Instant availableAt) {
        // Ingress only accepts UUID delivery IDs. An internal row has no event_action, so the
        // signed payload fingerprint continues to deduplicate only the original delivery.
        jdbc.update("""
                INSERT INTO inbox_events (id, delivery_id, event_type, installation_id, repository_id,
                    pr_number, head_sha, payload_sha256, correlation_id, verified_at, max_attempts, available_at)
                SELECT ?, 'review-assessment:' || id::text, ?, installation_id, repository_id,
                    pr_number, head_sha, payload_sha256, correlation_id, verified_at, max_attempts, ?
                FROM inbox_events WHERE id = ?
                ON CONFLICT (delivery_id) DO NOTHING
                """, UUID.randomUUID(), REVIEW_ASSESSMENT_EVENT, Timestamp.from(availableAt), event.lease().id());
    }

    void enqueue(Event event, Intent intent) {
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
                    expected_head_sha, command_type, target_provider, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (command_key) DO NOTHING
                """, UUID.randomUUID(), intent.key(scope), cycle, scope.installationId(), scope.repositoryId(),
                intent.expectedHeadSha(), intent.type(), intent.provider(), event.lease().correlationId());
    }

    @Override
    public boolean executeCommand(Command command, Runnable authorize, Supplier<Outcome> operation) {
        return transaction(() -> {
            if (!lock(command.lease())) return false;
            authorize.run();
            // Holding the row lock prevents another worker stealing the lease during a bounded API call.
            Outcome outcome = java.util.Objects.requireNonNull(operation.get());
            finish(command.lease(), outcome.name(), null);
            return true;
        });
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
        if (lease.token() == null) return false;
        boolean acquired = !jdbc.queryForList("SELECT id FROM " + table(lease.queue()) + """
                 WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                 AND locked_until > clock_timestamp() FOR UPDATE
                """, UUID.class, lease.id(), lease.token()).isEmpty();
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
