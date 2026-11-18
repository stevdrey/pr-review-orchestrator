package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubReviewNormalizer;
import io.github.stevdrey.prrevieworchestrator.application.*;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ReviewEvidenceIntegrationTest {
    static final String HEAD = "a".repeat(40);
    static final String NEXT = "b".repeat(40);
    static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    static final Instant TIME = Instant.parse("2026-11-16T11:00:00Z");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(Objects.requireNonNull(
            System.getProperty("test.postgres.image"))).asCompatibleSubstituteFor("postgres"));
    JdbcTemplate jdbc;
    DataSource dataSource;
    IngestReviewEvidence ingestion;
    UUID firstCycle;
    UUID secondCycle;
    String json;

    @BeforeEach
    void setup() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE installations CASCADE");
        jdbc.update("INSERT INTO installations (installation_id, account_id) VALUES (101, 1), (202, 2)");
        jdbc.update("INSERT INTO installation_repositories (installation_id, repository_id, enabled) VALUES (101, 1001, true), (202, 2002, true)");
        firstCycle = cycle(HEAD, 1);
        secondCycle = cycle(NEXT, 2);
        ingestion = newIngestion();
        try (var stream = getClass().getResourceAsStream("/github/review-snapshot.json")) {
            // This suite isolates inline projection; general-only bodies have their own suite.
            json = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("Review summary, not a clean verdict", "");
        }
    }

    IngestReviewEvidence newIngestion() {
        return new IngestReviewEvidence(new RepositoryAuthorization(Set.of(PR.scope()), new JdbcInstallationAuthorization(jdbc)),
                new JdbcReviewEvidenceStore(jdbc, new DataSourceTransactionManager(dataSource)));
    }

    UUID cycle(String sha, int iteration) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration)
                VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github', ?)
                """, id, sha, iteration);
        return id;
    }

    Snapshot snapshot(UUID id, UUID cycle, String sha, Instant at, String content) {
        return new GitHubReviewNormalizer().normalize(id, cycle, PR, sha, at,
                content.getBytes(StandardCharsets.UTF_8), Map.of("C1", Severity.HIGH));
    }

    @Test
    void editsResolutionAndOutOfOrderReadsRetainHistoryWithoutFixingFinding() {
        var original = snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json);
        var edited = snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME.plusSeconds(2), json
                .replace("export a secret", "changed comment text").replace("\"isResolved\": false", "\"isResolved\": true"));
        assertTrue(ingestion.ingest(original));
        jdbc.update("""
                UPDATE findings SET status = 'VERIFIED_FIXED', evidence_sha = ?, verified_at = now(),
                    disposition_actor = 'fixture-verifier', disposition_reason = 'Fixture evidence'
                """, NEXT);
        assertFalse(ingestion.ingest(original));
        assertEquals("VERIFIED_FIXED", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertTrue(ingestion.ingest(edited));
        // A delayed older read remains in history but must not revert current resolution or body revision.
        assertTrue(ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME.plusSeconds(1), json)));
        assertEquals(3, count("review_evidence_snapshots"));
        assertEquals(12, count("review_evidence_sources"));
        assertEquals(1, count("finding_identities"));
        assertEquals(1, count("findings"));
        var finding = jdbc.queryForMap("SELECT * FROM findings");
        assertEquals("OPEN", finding.get("status"));
        assertEquals(true, finding.get("thread_resolved"));
        assertEquals("HIGH", finding.get("severity"));
        assertEquals(edited.id(), finding.get("evidence_snapshot_id"));
        assertNull(finding.get("evidence_sha"));
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT body_sha256) FROM review_evidence_sources WHERE source_id = 'C1'", Integer.class));
        assertEquals(0, count("pr_review_states"));
        assertEquals(0, count("outbox_commands"));
    }

    @Test
    void unchangedReReadKeepsDispositionAndNullCommitIsStoredAsStale() {
        ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json));
        jdbc.update("""
                UPDATE findings SET status = 'DISMISSED', disposition_actor = 'fixture-human',
                    disposition_reason = 'Fixture dismissal'
                """);
        // Only thread resolution and an unrelated reply's commit change; the root comment revision is unchanged.
        var reread = snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME.plusSeconds(1), json
                .replace("\"isResolved\": false", "\"isResolved\": true")
                .replace("\"originalCommit\": { \"oid\": \"" + HEAD + "\" }", "\"originalCommit\": null")
                .replace("\"pullRequestReview\": { \"id\": \"R1\" }, \"replyTo\": { \"id\": \"C1\" }",
                        "\"pullRequestReview\": null, \"replyTo\": { \"id\": \"C1\" }")
                .replace("\"commit\": { \"oid\": \"" + HEAD + "\" },\n        \"originalCommit\": null",
                        "\"commit\": null,\n        \"originalCommit\": null"));
        assertTrue(ingestion.ingest(reread));
        var finding = jdbc.queryForMap("SELECT * FROM findings");
        assertEquals("DISMISSED", finding.get("status"));
        assertEquals("fixture-human", finding.get("disposition_actor"));
        assertEquals(true, finding.get("thread_resolved"));
        assertEquals(reread.id(), finding.get("evidence_snapshot_id"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM review_evidence_sources WHERE snapshot_id = ? AND source_id = 'C2'
                    AND reviewed_sha IS NULL AND original_sha IS NULL AND review_id IS NULL
                """, Integer.class, reread.id()));
    }

    @Test
    void sameFindingAcrossRoundsKeepsStableIdentityAndStaleSourceCommit() {
        ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json));
        String newHead = json.replace("\"headRefOid\": \"" + HEAD, "\"headRefOid\": \"" + NEXT)
                .replace("\"isOutdated\": false", "\"isOutdated\": true")
                .replace("\"isResolved\": false", "\"isResolved\": true");
        var next = snapshot(UUID.randomUUID(), secondCycle, NEXT, TIME.plusSeconds(1), newHead);
        ingestion.ingest(next);
        assertEquals(2, count("findings"));
        assertEquals(1, count("finding_identities"));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT stable_finding_id) FROM findings", Integer.class));
        var stale = jdbc.queryForMap("""
                SELECT f.reviewed_sha AS round_sha, s.reviewed_sha AS source_sha, s.original_sha, s.thread_outdated, f.status
                FROM findings f JOIN review_evidence_sources s ON s.snapshot_id = f.evidence_snapshot_id AND s.source_id = f.source_id
                WHERE f.review_cycle_id = ?
                """, secondCycle);
        assertEquals(NEXT, stale.get("round_sha"));
        assertEquals(HEAD, stale.get("source_sha"));
        assertEquals("c".repeat(40), stale.get("original_sha"));
        assertEquals(true, stale.get("thread_outdated"));
        assertEquals("OPEN", stale.get("status"));
        assertTrue(next.sources().stream().filter(s -> s.id().equals("C1")).findFirst().orElseThrow().staleFor(NEXT));
    }

    @Test
    void concurrentRetriesHaveOneSnapshotAndIdentityAndSurviveStoreRecreation() throws Exception {
        var snapshot = snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> ingestion.ingest(snapshot)));
            int inserted = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) inserted++;
            assertEquals(1, inserted);
        }
        assertFalse(newIngestion().ingest(snapshot));
        assertEquals(1, count("review_evidence_snapshots"));
        assertEquals(1, count("findings"));
        assertEquals(1, count("finding_identities"));
        assertThrows(Conflict.class, () -> ingestion.ingest(snapshot(snapshot.id(), firstCycle, HEAD, TIME,
                json.replace("Looks good", "Edited reply"))));
        assertThrows(Conflict.class, () -> ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json)));
    }

    @Test
    void cycleShaPrAndAuthorizationAreCheckedIncludingOnDuplicate() {
        var snapshot = snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json);
        assertTrue(ingestion.ingest(snapshot));
        jdbc.update("UPDATE installation_repositories SET enabled = false WHERE installation_id = 101");
        assertThrows(AuthorizationDenied.class, () -> ingestion.ingest(snapshot));
        jdbc.update("UPDATE installation_repositories SET enabled = true WHERE installation_id = 101");
        assertThrows(Conflict.class, () -> ingestion.ingest(snapshot(UUID.randomUUID(), secondCycle, HEAD, TIME, json)));
        var wrongPr = new Snapshot(UUID.randomUUID(), firstCycle, new PullRequestId(PR.scope(), 8),
                "github", HEAD, TIME, snapshot.sources());
        assertThrows(Conflict.class, () -> ingestion.ingest(wrongPr));
        var foreign = new Snapshot(UUID.randomUUID(), firstCycle,
                new PullRequestId(new RepositoryScope(202, 2002), 7), "github", HEAD, TIME, snapshot.sources());
        assertThrows(AuthorizationDenied.class, () -> ingestion.ingest(foreign));
        assertEquals(1, count("review_evidence_snapshots"));
    }

    @Test
    void commitFailureRollsBackAllEvidenceAndSanitizesDiagnostic() {
        jdbc.execute("""
                CREATE FUNCTION reject_review_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private-review-fixture-diagnostic'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_review_fixture AFTER INSERT ON findings
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_review_fixture()
                """);
        try {
            var failure = assertThrows(Unavailable.class, () -> ingestion.ingest(
                    snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json)));
            assertNull(failure.getCause());
            assertEquals("Review evidence unavailable", failure.getMessage());
            assertTrue(failure.retryable());
            for (String table : List.of("review_evidence_snapshots", "review_evidence_sources", "finding_identities", "findings")) {
                assertEquals(0, count(table), table);
            }
        } finally {
            jdbc.execute("DROP TRIGGER reject_review_fixture ON findings");
            jdbc.execute("DROP FUNCTION reject_review_fixture()");
        }
    }

    @Test
    void missingCommentsDoNotMeanDeletedOrFixedAndEmptyThreadsStillKeepResolution() {
        ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json));
        var thread = new Source("T1", Kind.REVIEW_THREAD, null, "T1", null, null, null, null, null,
                null, null, true, true, "src/Example.java", null, 10, Severity.UNKNOWN);
        ingestion.ingest(new Snapshot(UUID.randomUUID(), firstCycle, PR, "github", HEAD, TIME.plusSeconds(1), List.of(thread)));
        assertEquals(1, count("findings"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM findings", String.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM review_evidence_sources WHERE source_kind = 'REVIEW_THREAD'
                    AND thread_resolved AND thread_outdated AND reviewed_sha IS NULL
                """, Integer.class));
    }

    @Test
    void databaseRejectsCrossRoundEvidenceAndCrossPrIdentity() {
        ingestion.ingest(snapshot(UUID.randomUUID(), firstCycle, HEAD, TIME, json));
        var second = snapshot(UUID.randomUUID(), secondCycle, NEXT, TIME.plusSeconds(1),
                json.replace("\"headRefOid\": \"" + HEAD, "\"headRefOid\": \"" + NEXT));
        ingestion.ingest(second);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE findings SET evidence_snapshot_id = ? WHERE review_cycle_id = ?", second.id(), firstCycle));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE findings SET pr_number = 8"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE findings SET source_provider = 'other'"));
    }

    @Test
    void v5FindingsUpgradeWithoutChangingTheirProvenanceOrDisposition() {
        String schema = "review_upgrade_fixture";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("5").load().migrate();
            jdbc.update("INSERT INTO review_upgrade_fixture.installations (installation_id, account_id) VALUES (101, 1)");
            jdbc.update("INSERT INTO review_upgrade_fixture.installation_repositories (installation_id, repository_id) VALUES (101, 1001)");
            UUID cycle = UUID.randomUUID();
            UUID finding = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO review_upgrade_fixture.review_cycles
                        (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                    VALUES (?, 101, 1001, 7, ?, 'BASELINE', 'github')
                    """, cycle, HEAD);
            jdbc.update("""
                    INSERT INTO review_upgrade_fixture.findings (id, review_cycle_id, installation_id, repository_id,
                        reviewed_sha, source_provider, source_kind, source_id, thread_id, thread_resolved)
                    VALUES (?, ?, 101, 1001, ?, 'github', 'REVIEW_COMMENT', 'legacy-comment', 'legacy-thread', true)
                    """, finding, cycle, HEAD);
            var latest = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            assertEquals(6, latest.migrate().migrationsExecuted);
            var row = jdbc.queryForMap("SELECT * FROM review_upgrade_fixture.findings WHERE id = ?", finding);
            assertEquals("legacy-comment", row.get("source_id"));
            assertEquals("legacy-thread", row.get("thread_id"));
            assertEquals(true, row.get("thread_resolved"));
            assertEquals("OPEN", row.get("status"));
            assertEquals(HEAD, row.get("reviewed_sha"));
            assertNull(row.get("stable_finding_id"));
            assertNull(row.get("evidence_snapshot_id"));
            assertTrue(latest.validateWithResult().validationSuccessful);
            assertEquals(0, latest.migrate().migrationsExecuted);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS review_upgrade_fixture CASCADE"); }
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
