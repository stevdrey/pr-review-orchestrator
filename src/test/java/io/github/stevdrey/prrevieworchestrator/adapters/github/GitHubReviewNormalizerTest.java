package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class GitHubReviewNormalizerTest {
    static final String HEAD = "a".repeat(40);
    static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 7);
    final GitHubReviewNormalizer normalizer = new GitHubReviewNormalizer();

    @Test
    void preservesProvenanceWithoutTrustingProseOrTreatingRepliesAsFindings() throws Exception {
        var snapshot = normalize(fixture(), Map.of("C1", Severity.HIGH));
        assertEquals(4, snapshot.sources().size());
        var root = source(snapshot, "C1");
        assertTrue(root.candidate());
        assertEquals("github", snapshot.provider());
        assertEquals("R1", root.reviewId());
        assertEquals("T1", root.threadId());
        assertEquals("reviewer", root.author());
        assertEquals(HEAD, root.reviewedSha());
        assertEquals("c".repeat(40), root.originalSha());
        assertEquals(Severity.HIGH, root.severity());
        assertEquals(64, root.bodySha256().length());
        assertFalse(root.staleFor(HEAD));
        assertTrue(root.staleFor("b".repeat(40)));
        assertFalse(source(snapshot, "C2").candidate());
        assertNull(source(snapshot, "C2").author());
        assertEquals("C1", source(snapshot, "C2").replyToId());
        assertFalse(source(snapshot, "R1").candidate());
        assertEquals("CHANGES_REQUESTED", source(snapshot, "R1").reviewState());
        assertNull(source(snapshot, "T1").reviewedSha());
        assertFalse(snapshot.toString().contains("export a secret"));
    }

    @Test
    void resolutionOutdatedFlagAndEditedBodyRemainIndependentSignals() throws Exception {
        var before = normalize(fixture(), Map.of());
        var after = normalize(fixture().replace("\"isResolved\": false", "\"isResolved\": true")
                .replace("\"isOutdated\": false", "\"isOutdated\": true")
                .replace("export a secret", "do something else"), Map.of());
        assertEquals(source(before, "C1").id(), source(after, "C1").id());
        assertNotEquals(source(before, "C1").bodySha256(), source(after, "C1").bodySha256());
        assertTrue(source(after, "C1").resolved());
        assertTrue(source(after, "C1").staleFor(HEAD));
        assertEquals(Severity.UNKNOWN, source(after, "C1").severity());
    }

    @Test
    void rejectsPartialPaginatedWrongRepositoryWrongPrMissingShaAndMalformedResponses() throws Exception {
        String fixture = fixture();
        for (String invalid : List.of(
                fixture.replace("\"hasNextPage\": false", "\"hasNextPage\": true"),
                fixture.replace("\"databaseId\": 1001", "\"databaseId\": 2002"),
                fixture.replace("\"number\": 7", "\"number\": 8"),
                fixture.replace("\"headRefOid\": \"" + HEAD, "\"headRefOid\": \"b" + HEAD.substring(1)),
                fixture.replace("\"commit\": { \"oid\": \"" + HEAD + "\" }", "\"commit\": null"),
                fixture.replace("\"line\": 12", "\"line\": -1"),
                fixture.replace("\"id\": \"C2\"", "\"id\": \"C1\""),
                fixture.replace("\"replyTo\": null,", ""),
                fixture.replace("\"author\": null,", ""),
                fixture.replace("\"number\": 7", "\"number\": 4294967303"),
                fixture.replace("\"isResolved\": false", "\"isResolved\": \"false\""),
                "{\"errors\":[]," + fixture.substring(1), fixture + "{}", "{\"data\":1,\"data\":2}")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> normalize(invalid, Map.of()));
            assertEquals("Incomplete or invalid GitHub review snapshot", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    private Snapshot normalize(String json, Map<String, Severity> severity) {
        return normalizer.normalize(UUID.randomUUID(), UUID.randomUUID(), PR, HEAD,
                Instant.parse("2026-11-16T11:00:00Z"), json.getBytes(StandardCharsets.UTF_8), severity);
    }
    private Source source(Snapshot s, String id) {
        return s.sources().stream().filter(source -> source.id().equals(id)).findFirst().orElseThrow();
    }
    String fixture() throws Exception {
        try (var stream = GitHubReviewNormalizerTest.class.getResourceAsStream("/github/review-snapshot.json")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
