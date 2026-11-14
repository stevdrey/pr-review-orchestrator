package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.VerifiedDelivery;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcDeliveryInbox implements DeliveryInbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcDeliveryInbox(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        // Leave time for the HTTP acknowledgement; no provider calls occur inside this transaction.
        transactions.setTimeout(3);
    }

    @Override
    public Receipt accept(VerifiedDelivery event) {
        return transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO inbox_events (id, delivery_id, event_type, event_action, installation_id,
                        repository_id, pr_number, head_sha, payload_sha256, correlation_id, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, event.id(), event.deliveryId(), event.eventType(), event.action(),
                    event.scope().installationId(), event.scope().repositoryId(), event.prNumber(),
                    event.headSha(), event.payloadSha256(), event.correlationId(), Timestamp.from(event.verifiedAt()));
            if (inserted == 1) {
                return new Receipt(false, event.correlationId());
            }
            // The unique indexes serialize concurrent retries. Both IDs and signed payload fingerprints
            // are checked because GitHub's HMAC authenticates the body, not delivery/event headers.
            var matches = jdbc.query("""
                    SELECT delivery_id, event_type, installation_id, repository_id, payload_sha256, correlation_id
                    FROM inbox_events WHERE delivery_id = ? OR
                        (installation_id = ? AND repository_id = ? AND payload_sha256 = ? AND event_action IS NOT NULL)
                    """, (rs, row) -> {
                        boolean same = event.eventType().equals(rs.getString("event_type"))
                                && event.scope().installationId() == rs.getLong("installation_id")
                                && event.scope().repositoryId() == rs.getLong("repository_id")
                                && event.payloadSha256().equals(rs.getString("payload_sha256"));
                        if (!same) {
                            throw new Conflict();
                        }
                        return new Receipt(true, rs.getObject("correlation_id", UUID.class));
                    }, event.deliveryId(), event.scope().installationId(), event.scope().repositoryId(), event.payloadSha256());
            if (matches.size() != 1) {
                throw new Conflict();
            }
            return matches.getFirst();
        });
    }
}
