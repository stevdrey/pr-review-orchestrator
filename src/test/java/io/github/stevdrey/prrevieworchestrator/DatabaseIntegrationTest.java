package io.github.stevdrey.prrevieworchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseIntegrationTest {
    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(40);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    private Connection connection;
    private UUID cycleId;

    @BeforeEach
    void seedIsolatedTransaction() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        execute("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        execute("INSERT INTO installation_repositories (installation_id, repository_id) VALUES (101, 1001), (101, 2002), (202, 1001)");
        cycleId = UUID.randomUUID();
        execute("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'fixture-provider')
                """, cycleId, HEAD);
    }

    @AfterEach
    void rollbackFixtures() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void healthEndpointReportsUpWithPostgres() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("UP", JsonPath.read(response.body(), "$.status"), response.body());
        }
    }

    @Test
    void emptyDatabaseMigratesAndRepeatedStartupDoesNotReapplyMigration() throws SQLException {
        // Spring Boot has already migrated this fresh container before creating the context.
        assertEquals("1", flyway.info().current().getVersion().toString());
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(1, count("SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals(2, count("SELECT count(*) FROM installations"));
        assertTrue(flyway.getConfiguration().isCleanDisabled());
        assertThrows(FlywayException.class, flyway::clean);
    }

    @Test
    void deliveryCommandAndCycleKeysRejectReplays() throws SQLException {
        String delivery = UUID.randomUUID().toString();
        insertEvent(UUID.randomUUID(), delivery);
        reject("23505", () -> insertEvent(UUID.randomUUID(), delivery));

        String key = "101/1001/7/" + HEAD + "/BASELINE/fixture-provider";
        insertCommand(UUID.randomUUID(), key, HEAD);
        reject("23505", () -> insertCommand(UUID.randomUUID(), key, HEAD));
        reject("23505", () -> execute("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'fixture-provider')
                """, UUID.randomUUID(), HEAD));
    }

    @Test
    void referencesRejectForeignInstallationsRepositoriesAndSha() throws SQLException {
        UUID finding = insertFinding("ISSUE_COMMENT", null);
        reject("23503", () -> execute("UPDATE findings SET installation_id = 202 WHERE id = ?", finding));
        reject("23503", () -> execute("UPDATE findings SET repository_id = 2002 WHERE id = ?", finding));
        reject("23503", () -> execute("UPDATE findings SET reviewed_sha = ? WHERE id = ?", NEXT_HEAD, finding));
        reject("23503", () -> insertCommand(UUID.randomUUID(), "stale", NEXT_HEAD));
        reject("23503", () -> execute("UPDATE review_cycles SET repository_id = 9999 WHERE id = ?", cycleId));

        UUID event = UUID.randomUUID();
        insertEvent(event, UUID.randomUUID().toString());
        reject("23503", () -> execute("UPDATE inbox_events SET repository_id = 9999 WHERE id = ?", event));

        UUID audit = UUID.randomUUID();
        insertAudit(audit);
        reject("23503", () -> execute("UPDATE audit_entries SET installation_id = 202 WHERE id = ?", audit));
        reject("23514", () -> execute("UPDATE audit_entries SET head_sha = NULL WHERE id = ?", audit));
    }

    @Test
    void generalCommentsAndThreadResolutionRemainSeparateFromVerifiedFixes() throws SQLException {
        UUID general = insertFinding("ISSUE_COMMENT", null);
        UUID second = insertFinding("ISSUE_COMMENT", null);
        execute("UPDATE findings SET source_id = (SELECT source_id FROM findings WHERE id = ?) WHERE id = ?", general, second);
        execute("UPDATE findings SET status = 'ACKNOWLEDGED' WHERE id = ?", second);
        assertEquals(1, count("SELECT count(*) FROM findings WHERE id = ? AND status = 'OPEN'", general));
        reject("23514", () -> execute("UPDATE findings SET thread_resolved = true WHERE id = ?", general));
        reject("23514", () -> execute("UPDATE findings SET thread_id = 'unexpected-thread' WHERE id = ?", general));
        reject("23514", () -> insertFinding("REVIEW_THREAD", null));

        UUID inline = insertFinding("REVIEW_THREAD", "fixture-thread");
        execute("UPDATE findings SET thread_resolved = true WHERE id = ?", inline);
        assertEquals(1, count("SELECT count(*) FROM findings WHERE thread_resolved AND status = 'OPEN'"));
        reject("23514", () -> execute("UPDATE findings SET status = 'VERIFIED_FIXED' WHERE id = ?", inline));
        reject("23514", () -> execute("""
                UPDATE findings SET status = 'VERIFIED_FIXED', disposition_actor = 'fixture-reviewer',
                    disposition_reason = 'Rechecked changed code' WHERE id = ?
                """, inline));
        execute("""
                UPDATE findings SET status = 'VERIFIED_FIXED', evidence_sha = ?, verified_at = now(),
                    disposition_actor = 'fixture-reviewer', disposition_reason = 'Rechecked changed code' WHERE id = ?
                """, NEXT_HEAD, inline);
        assertEquals(1, count("SELECT count(*) FROM findings WHERE status = 'VERIFIED_FIXED'"));
    }

    @Test
    void retryBoundsAndEvidenceFormatsRejectInvalidData() throws SQLException {
        UUID command = UUID.randomUUID();
        insertCommand(command, "bounded-command", HEAD);
        reject("23514", () -> execute("UPDATE outbox_commands SET attempts = -1 WHERE id = ?", command));
        reject("23514", () -> execute("UPDATE outbox_commands SET attempts = max_attempts + 1 WHERE id = ?", command));
        reject("23514", () -> execute("UPDATE outbox_commands SET max_attempts = 0 WHERE id = ?", command));
        UUID event = UUID.randomUUID();
        insertEvent(event, UUID.randomUUID().toString());
        reject("23514", () -> execute("UPDATE inbox_events SET attempts = max_attempts + 1 WHERE id = ?", event));
        reject("23514", () -> execute("UPDATE inbox_events SET payload_sha256 = 'not-a-digest' WHERE id = ?", event));
        reject("23514", () -> execute("UPDATE review_cycles SET head_sha = 'main' WHERE id = ?", cycleId));
        reject("23514", () -> execute("UPDATE review_cycles SET iteration = 0 WHERE id = ?", cycleId));
    }

    @Test
    void installationLevelEventsAndAuditDoNotRequireRepository() throws SQLException {
        execute("""
                INSERT INTO inbox_events (id, delivery_id, event_type, installation_id, payload_sha256, correlation_id, verified_at)
                VALUES (?, ?, 'installation', 101, ?, ?, now())
                """, UUID.randomUUID(), UUID.randomUUID().toString(), "c".repeat(64), UUID.randomUUID());
        execute("""
                INSERT INTO audit_entries (id, installation_id, correlation_id, actor, action, outcome)
                VALUES (?, 101, ?, 'fixture-operator', 'INSTALLATION_SUSPENDED', 'RECORDED')
                """, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(1, count("SELECT count(*) FROM inbox_events WHERE repository_id IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM audit_entries WHERE repository_id IS NULL"));
    }

    @Test
    void eventCommandAndAuditWritesRollbackTogether() throws SQLException {
        UUID event = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        UUID audit = UUID.randomUUID();
        insertEvent(event, UUID.randomUUID().toString());
        insertCommand(command, "transaction-fixture", HEAD);
        insertAudit(audit);
        assertEquals(1, count("SELECT count(*) FROM inbox_events WHERE id = ?", event));
        assertEquals(1, count("SELECT count(*) FROM outbox_commands WHERE id = ?", command));
        assertEquals(1, count("SELECT count(*) FROM audit_entries WHERE id = ?", audit));
        connection.rollback();
        try (var independent = dataSource.getConnection(); var statement = independent.createStatement()) {
            for (String table : new String[] {"inbox_events", "outbox_commands", "audit_entries"}) {
                try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    result.next();
                    assertEquals(0, result.getInt(1), table);
                }
            }
        }
    }

    private void insertEvent(UUID id, String delivery) throws SQLException {
        execute("""
                INSERT INTO inbox_events (id, delivery_id, event_type, installation_id, repository_id,
                    head_sha, payload_sha256, correlation_id, verified_at)
                VALUES (?, ?, 'pull_request', 101, 1001, ?, ?, ?, now())
                """, id, delivery, HEAD, "c".repeat(64), UUID.randomUUID());
    }

    private void insertCommand(UUID id, String key, String sha) throws SQLException {
        execute("""
                INSERT INTO outbox_commands (id, command_key, review_cycle_id, installation_id, repository_id,
                    expected_head_sha, command_type, target_provider, correlation_id)
                VALUES (?, ?, ?, 101, 1001, ?, 'REQUEST_REVIEW', 'fixture-provider', ?)
                """, id, key, cycleId, sha, UUID.randomUUID());
    }

    private UUID insertFinding(String kind, String thread) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, reviewed_sha,
                    source_provider, source_kind, source_id, thread_id)
                VALUES (?, ?, 101, 1001, ?, 'fixture-provider', ?, ?, ?)
                """, id, cycleId, HEAD, kind, UUID.randomUUID().toString(), thread);
        return id;
    }

    private void insertAudit(UUID id) throws SQLException {
        execute("""
                INSERT INTO audit_entries (id, installation_id, repository_id, review_cycle_id, head_sha,
                    correlation_id, actor, action, outcome)
                VALUES (?, 101, 1001, ?, ?, ?, 'fixture-operator', 'REVIEW_REQUESTED', 'QUEUED')
                """, id, cycleId, HEAD, UUID.randomUUID());
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private int count(String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void reject(String sqlState, SqlAction action) throws SQLException {
        var savepoint = connection.setSavepoint();
        SQLException failure = assertThrows(SQLException.class, action::run);
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
        assertEquals(sqlState, failure.getSQLState(), failure.getMessage());
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
