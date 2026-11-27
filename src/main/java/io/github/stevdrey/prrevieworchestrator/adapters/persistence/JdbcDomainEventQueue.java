package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher.Outcome;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventQueue;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcDomainEventQueue implements DomainEventQueue {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration TRANSACTION_DEADLINE = Duration.ofSeconds(8);
    private static final Duration POST_BUDGET = Duration.ofSeconds(4);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcDomainEventQueue(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(Math.toIntExact(TRANSACTION_DEADLINE.toSeconds()));
    }

    @Override
    public Optional<Claim> claim() {
        return transaction(() -> jdbc.query("""
                WITH next AS (
                    SELECT subscription_id, event_id FROM pr_event_deliveries
                    WHERE status = 'PENDING' AND available_at <= statement_timestamp()
                    ORDER BY available_at, event_id, subscription_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE pr_event_deliveries d SET
                    status = CASE WHEN attempts = ? THEN 'DEAD' ELSE 'PENDING' END,
                    last_outcome = CASE WHEN attempts = ? THEN 'LEASE_EXPIRED' ELSE 'IN_FLIGHT' END,
                    attempts = least(attempts + 1, ?),
                    available_at = clock_timestamp() + interval '30 seconds'
                FROM next WHERE (d.subscription_id, d.event_id) = (next.subscription_id, next.event_id)
                RETURNING d.subscription_id, d.event_id, d.attempts, d.status
                """, rs -> {
            if (!rs.next() || "DEAD".equals(rs.getString("status"))) return Optional.empty();
            return Optional.of(new Claim(rs.getObject("subscription_id", UUID.class),
                    rs.getObject("event_id", UUID.class), rs.getInt("attempts")));
        }, MAX_ATTEMPTS, MAX_ATTEMPTS, MAX_ATTEMPTS));
    }

    @Override
    public void deliver(Claim claim, Consumer<RepositoryScope> authorize, DomainEventPublisher publisher) {
        long started = System.nanoTime();
        transaction(() -> {
            eventForClaim(claim).ifPresent(event -> send(claim, event, authorize, publisher, started));
            return null;
        });
    }

    private Optional<PrDomainEvent> eventForClaim(Claim claim) {
        // Hold the delivery row lock through receipt persistence. The committed attempt fences a stale worker.
        return jdbc.query("""
                SELECT e.id, e.transition_id, e.installation_id, e.repository_id, e.pr_number,
                    t.resulting_head_sha, t.resulting_version, t.action, t.resulting_state, t.ci_status, t.occurred_at
                FROM pr_event_deliveries d
                JOIN pr_domain_events e ON e.id = d.event_id
                JOIN pr_review_transitions t USING (installation_id, repository_id, pr_number, transition_id)
                WHERE d.subscription_id = ? AND d.event_id = ? AND d.attempts = ?
                    AND d.status = 'PENDING' AND d.last_outcome = 'IN_FLIGHT'
                    AND d.available_at > clock_timestamp()
                FOR UPDATE OF d
                """, (rs, row) -> {
            String ci = rs.getString("ci_status");
            return new PrDomainEvent(rs.getObject("id", UUID.class),
                    new PullRequestId(new RepositoryScope(rs.getLong("installation_id"), rs.getLong("repository_id")),
                            rs.getInt("pr_number")),
                    rs.getString("resulting_head_sha"), rs.getLong("resulting_version"),
                    rs.getObject("transition_id", UUID.class),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                    Action.valueOf(rs.getString("action")), State.valueOf(rs.getString("resulting_state")),
                    ci == null ? null : Ci.valueOf(ci));
        }, claim.subscriptionId(), claim.eventId(), claim.attempt()).stream().findFirst();
    }

    private void send(Claim claim, PrDomainEvent event, Consumer<RepositoryScope> authorize,
            DomainEventPublisher publisher, long started) {
        var pr = event.pr();
        var scope = pr.scope();
        // A committed revocation prevents every subsequent send, including retries already claimed.
        var target = jdbc.queryForList("""
                SELECT callback_sha256 FROM pr_event_subscriptions
                WHERE id = ? AND installation_id = ? AND repository_id = ?
                    AND (pr_number IS NULL OR pr_number = ?) AND revoked_at IS NULL
                FOR SHARE
                """, String.class, claim.subscriptionId(), scope.installationId(), scope.repositoryId(), pr.number());
        if (target.isEmpty()) {
            settle(claim, "CANCELLED", "REVOKED");
            return;
        }
        try {
            authorize.accept(scope);
        } catch (AuthorizationDenied ignored) {
            settle(claim, "CANCELLED", "AUTHORIZATION_DENIED");
            return;
        } catch (AuthorizationUnavailable ignored) {
            retry(claim, "AUTHORIZATION_UNAVAILABLE");
            return;
        }
        if (System.nanoTime() - started >= TRANSACTION_DEADLINE.minus(POST_BUDGET).toNanos()) {
            retry(claim, "RETRY");
            return;
        }
        Outcome outcome = publisher.publish(claim.subscriptionId(), target.getFirst(), event);
        switch (outcome) {
            case ACKNOWLEDGED -> settle(claim, "DELIVERED", "ACKNOWLEDGED");
            case REJECTED -> settle(claim, "DEAD", "REJECTED");
            case RETRY -> retry(claim, "RETRY");
        }
    }

    private void retry(Claim claim, String outcome) {
        settle(claim, claim.attempt() >= MAX_ATTEMPTS ? "DEAD" : "PENDING", outcome);
    }

    private void settle(Claim claim, String status, String outcome) {
        long delaySeconds = 5L << (claim.attempt() - 1);
        jdbc.update("""
                UPDATE pr_event_deliveries SET status = ?, last_outcome = ?,
                    available_at = clock_timestamp() + ? * interval '1 second'
                WHERE subscription_id = ? AND event_id = ?
                """, status, outcome, delaySeconds, claim.subscriptionId(), claim.eventId());
    }

    private <T> T transaction(Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                try {
                    PostgresDeadlines.apply(jdbc, TRANSACTION_DEADLINE);
                    return operation.get();
                } catch (RuntimeException ignored) {
                    throw unavailable();
                }
            });
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("PR domain event queue unavailable");
    }
}
