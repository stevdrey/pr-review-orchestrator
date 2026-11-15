package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import java.time.Duration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** One bounded-transaction mechanism for every PostgreSQL adapter that must cover deferred COMMIT work. */
public final class PostgresDeadlines {
    private PostgresDeadlines() {}

    /** PostgreSQL 17 introduced transaction_timeout; fail startup rather than every transaction. */
    public static void requireTransactionTimeout(JdbcTemplate jdbc, String purpose) {
        if (jdbc.queryForObject("SELECT current_setting('transaction_timeout', true)", String.class) == null) {
            throw new IllegalStateException("PostgreSQL 17+ transaction_timeout is required for " + purpose);
        }
    }

    /**
     * Must run inside the transaction. JDBC and statement timeouts do not cover deferred COMMIT work;
     * transaction_timeout does and resets at transaction end. The network timeout bounds waiting for the
     * commit response on this connection only; Hikari resets it on return to the pool.
     */
    public static void apply(JdbcTemplate jdbc, Duration deadline) {
        jdbc.execute("SET LOCAL transaction_timeout = '" + deadline.toMillis() + "ms'");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            connection.setNetworkTimeout(Runnable::run, Math.toIntExact(deadline.toMillis()));
            return null;
        });
    }
}
