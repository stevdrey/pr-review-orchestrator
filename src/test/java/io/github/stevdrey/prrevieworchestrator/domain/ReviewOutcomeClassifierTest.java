package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewOutcomeClassifierTest {
    final String head = "a".repeat(40);
    final PullRequestId pr = new PullRequestId(new RepositoryScope(101, 1001), 7);
    final Instant time = Instant.parse("2026-11-17T12:00:00Z");
    final Run run = new Run(pr, head, "codex", Stage.BASELINE, UUID.randomUUID(), UUID.randomUUID(), "github", "codex-bot");
    final ReviewOutcomeClassifier classifier = new ReviewOutcomeClassifier();

    Source review(String sha, String author, String state, Feedback feedback) {
        return new Source("R1", Kind.REVIEW, "R1", null, null, author, sha, null, "0".repeat(64),
                time, state, null, null, null, null, null, Severity.UNKNOWN, feedback);
    }
    Source cleanReview() { return review(head, run.reviewer(), "COMMENTED", Feedback.NONE); }
    Snapshot snapshot(Source... sources) {
        return new Snapshot(UUID.randomUUID(), run.cycleId(), pr, "github", head, time, List.of(sources));
    }
    Completion completion(Conclusion conclusion) { return new Completion(run, "R1", Status.COMPLETED, conclusion); }
    ReviewOutcome classify(Completion completion, Snapshot snapshot, PendingFindings pending) {
        return classifier.classify(run, 2, completion, snapshot, pending);
    }
    ReviewOutcome classify(Source... sources) {
        return classify(completion(Conclusion.NO_FINDINGS), snapshot(sources), PendingFindings.CLEAR);
    }
    void assertBlocked(Verdict verdict, ReviewOutcome outcome) {
        assertEquals(verdict, outcome.verdict());
        assertEquals(ReviewConflict.Code.GUARD_REJECTED,
                assertThrows(ReviewConflict.class, () -> outcome.cleanTransition(UUID.randomUUID(),
                        new ReviewTransition.Gates(true, false, false, true, false, ReviewTransition.Ci.PASSED))).code());
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void cleanIsBoundToProviderStageRequestCycleShaAndVersion(Stage stage) {
        Run expected = new Run(pr, head, "codex", stage, run.requestId(), run.cycleId(), "github", run.reviewer());
        var outcome = classifier.classify(expected, 4, new Completion(expected, "R1", Status.COMPLETED, Conclusion.NO_FINDINGS),
                snapshot(cleanReview()), PendingFindings.CLEAR);
        assertEquals(CLEAN_FOR_SHA, outcome.verdict());
        assertEquals(expected, outcome.run());
        assertEquals(4, outcome.version());
        var command = outcome.cleanTransition(UUID.randomUUID(),
                new ReviewTransition.Gates(true, false, false, false, false, ReviewTransition.Ci.UNKNOWN));
        assertEquals(stage == Stage.BASELINE ? ReviewTransition.Action.BASELINE_CLEAN : ReviewTransition.Action.DEEP_CLEAN, command.action());
        assertEquals(head, command.expectedHeadSha());
        assertEquals(4, command.expectedVersion());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void providerFailuresTimeoutPendingAndUnknownNeverProduceClean(Status status) {
        assertBlocked(UNKNOWN, classify(new Completion(run, "R1", status, Conclusion.NO_FINDINGS),
                snapshot(cleanReview()), PendingFindings.CLEAR));
    }

    @Test
    void missingCompletionOrReviewAndEmptySnapshotsNeverProveNoFindings() {
        assertBlocked(UNKNOWN, classify(null, snapshot(cleanReview()), PendingFindings.CLEAR));
        assertBlocked(UNKNOWN, classify(completion(Conclusion.NO_FINDINGS), null, PendingFindings.CLEAR));
        assertBlocked(UNKNOWN, classify());
        assertBlocked(UNKNOWN, classify(new Completion(run, null, Status.COMPLETED, Conclusion.NO_FINDINGS),
                snapshot(cleanReview()), PendingFindings.CLEAR));
    }

    @Test
    void wrongPrHeadProviderStageRequestCycleAndReviewerCannotBorrowCompletion() {
        for (Run other : List.of(
                new Run(new PullRequestId(pr.scope(), 8), head, "codex", Stage.BASELINE, run.requestId(), run.cycleId(), "github", run.reviewer()),
                new Run(pr, "b".repeat(40), "codex", Stage.BASELINE, run.requestId(), run.cycleId(), "github", run.reviewer()),
                new Run(pr, head, "other", Stage.BASELINE, run.requestId(), run.cycleId(), "github", run.reviewer()),
                new Run(pr, head, "codex", Stage.DEEP, run.requestId(), run.cycleId(), "github", run.reviewer()),
                new Run(pr, head, "codex", Stage.BASELINE, UUID.randomUUID(), run.cycleId(), "github", run.reviewer()),
                new Run(pr, head, "codex", Stage.BASELINE, run.requestId(), UUID.randomUUID(), "github", run.reviewer()),
                new Run(pr, head, "codex", Stage.BASELINE, run.requestId(), run.cycleId(), "other", run.reviewer()),
                new Run(pr, head, "codex", Stage.BASELINE, run.requestId(), run.cycleId(), "github", "other"))) {
            assertBlocked(UNKNOWN, classify(new Completion(other, "R1", Status.COMPLETED, Conclusion.NO_FINDINGS),
                    snapshot(cleanReview()), PendingFindings.CLEAR));
        }
        for (Snapshot other : List.of(
                new Snapshot(UUID.randomUUID(), run.cycleId(), new PullRequestId(pr.scope(), 8), "github", head, time, List.of(cleanReview())),
                new Snapshot(UUID.randomUUID(), UUID.randomUUID(), pr, "github", head, time, List.of(cleanReview())),
                new Snapshot(UUID.randomUUID(), run.cycleId(), pr, "other", head, time, List.of(cleanReview())),
                new Snapshot(UUID.randomUUID(), run.cycleId(), pr, "github", "b".repeat(40), time, List.of(cleanReview())))) {
            assertBlocked(UNKNOWN, classify(completion(Conclusion.NO_FINDINGS), other, PendingFindings.CLEAR));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
    void staleOrMissingReviewedCommitCannotBeReplacedByCurrentSnapshotHead(String sha) {
        assertBlocked(UNKNOWN, classify(review(sha, run.reviewer(), "APPROVED", Feedback.NONE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DISMISSED", "FUTURE_STATE"})
    void nonfinalOrUnsupportedReviewStatesAreUnknown(String state) {
        assertBlocked(UNKNOWN, classify(review(head, run.reviewer(), state, Feedback.NONE)));
    }

    @Test
    void actorMismatchDeletedActorAndReviewReferenceMismatchAreUnknown() {
        assertBlocked(UNKNOWN, classify(review(head, "author", "APPROVED", Feedback.NONE)));
        assertBlocked(UNKNOWN, classify(review(head, null, "APPROVED", Feedback.NONE)));
        assertBlocked(UNKNOWN, classify(new Completion(run, "R2", Status.COMPLETED, Conclusion.NO_FINDINGS),
                snapshot(cleanReview()), PendingFindings.CLEAR));
    }

    @Test
    void approvalAndUnstructuredNoFindingsNeverSubstituteForStructuredCompletion() {
        assertBlocked(NEEDS_HUMAN, classify(completion(Conclusion.UNSTRUCTURED),
                snapshot(review(head, run.reviewer(), "APPROVED", Feedback.NONE)), PendingFindings.CLEAR));
        assertBlocked(UNKNOWN, classify(completion(Conclusion.UNKNOWN), snapshot(cleanReview()), PendingFindings.CLEAR));
        assertBlocked(NEEDS_HUMAN, classify(review(head, run.reviewer(), "APPROVED", Feedback.NEEDS_HUMAN)));
    }

    @Test
    void findingsAndChangesRequestedOverrideNoFindingsConclusion() {
        assertBlocked(ACTION_REQUIRED, classify(review(head, run.reviewer(), "CHANGES_REQUESTED", Feedback.NONE)));
        assertBlocked(ACTION_REQUIRED, classify(review(head, run.reviewer(), "COMMENTED", Feedback.ACTIONABLE)));
        assertBlocked(ACTION_REQUIRED, classify(completion(Conclusion.FINDINGS), snapshot(cleanReview()), PendingFindings.CLEAR));
        var resolved = new Source("C1", Kind.REVIEW_COMMENT, "older-review", "T1", null, "reviewer", "b".repeat(40),
                null, "0".repeat(64), time, null, true, true, "file.java", null, 1, Severity.HIGH);
        assertBlocked(ACTION_REQUIRED, classify(cleanReview(), resolved));
    }

    @Test
    void closedInlineOccurrencesAndDismissedAmbiguousCompletionDoNotBlock() {
        var inline = new Source("C1", Kind.REVIEW_COMMENT, "older-review", "T1", null, "reviewer", "b".repeat(40),
                null, "0".repeat(64), time, null, true, true, "file.java", null, 1, Severity.HIGH);
        var completion = completion(Conclusion.NO_FINDINGS);
        assertEquals(CLEAN_FOR_SHA, classifier.classify(run, 2, completion, snapshot(cleanReview(), inline),
                PendingFindings.CLEAR, Set.of(), Set.of("C1")).verdict());
        // General dismissals never clear inline candidates.
        assertBlocked(ACTION_REQUIRED, classifier.classify(run, 2, completion, snapshot(cleanReview(), inline),
                PendingFindings.CLEAR, Set.of("C1"), Set.of()));
        var ambiguous = review(head, run.reviewer(), "COMMENTED", Feedback.NEEDS_HUMAN);
        assertEquals(CLEAN_FOR_SHA, classifier.classify(run, 2, completion, snapshot(ambiguous),
                PendingFindings.CLEAR, Set.of("R1"), Set.of()).verdict());
        // An actionable completion body contradicts NO_FINDINGS and cannot be dismissed into a clean result.
        var actionable = review(head, run.reviewer(), "COMMENTED", Feedback.ACTIONABLE);
        assertBlocked(ACTION_REQUIRED, classifier.classify(run, 2, completion, snapshot(actionable),
                PendingFindings.CLEAR, Set.of("R1"), Set.of()));
    }

    @Test
    void generalFollowUpCommentsAndRetainedFindingsCannotBeClearedByNewCleanReview() {
        var followUp = new Source("I1", Kind.ISSUE_COMMENT, null, null, null, "author", null, null,
                "0".repeat(64), time.minusSeconds(3600), null, null, null, null, null, null, Severity.UNKNOWN, Feedback.NEEDS_HUMAN);
        assertBlocked(NEEDS_HUMAN, classify(cleanReview(), followUp));
        assertBlocked(ACTION_REQUIRED, classify(completion(Conclusion.NO_FINDINGS), snapshot(cleanReview()), PendingFindings.ACTIONABLE));
        assertBlocked(NEEDS_HUMAN, classify(completion(Conclusion.NO_FINDINGS), snapshot(cleanReview()), PendingFindings.NEEDS_HUMAN));
        assertBlocked(UNKNOWN, classify(completion(Conclusion.NO_FINDINGS), snapshot(cleanReview()), PendingFindings.UNKNOWN));
    }

    @Test
    void sourceRevisionAfterObservationIsContradictory() {
        var future = new Snapshot(UUID.randomUUID(), run.cycleId(), pr, "github", head, time.minusSeconds(1), List.of(cleanReview()));
        assertBlocked(UNKNOWN, classify(completion(Conclusion.NO_FINDINGS), future, PendingFindings.CLEAR));
    }
}
