package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Reason.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Verdict.*;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.util.Objects;

/** Pure policy. No body text, keyword matcher, resolution shortcut or model decision is authoritative. */
public final class ReviewOutcomeClassifier {
    public ReviewOutcome classify(Run expected, long version, Completion completion,
            Snapshot snapshot, PendingFindings pending) {
        Objects.requireNonNull(expected); Objects.requireNonNull(pending);
        if (version < 0 || version == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid state version");
        if (pending == PendingFindings.ACTIONABLE) return result(expected, version, ACTION_REQUIRED, BLOCKING_FINDINGS);
        if (pending == PendingFindings.NEEDS_HUMAN) return result(expected, version, NEEDS_HUMAN, AMBIGUOUS_FEEDBACK);
        if (pending == PendingFindings.UNKNOWN) return result(expected, version, UNKNOWN, INCOMPLETE_EVIDENCE);
        if (completion == null) return result(expected, version, UNKNOWN, MISSING_COMPLETION);
        if (!expected.equals(completion.run())) return result(expected, version, UNKNOWN, UNCORRELATED_COMPLETION);
        if (completion.status() != Status.COMPLETED) return result(expected, version, UNKNOWN, PROVIDER_NOT_COMPLETED);
        if (snapshot == null || !snapshot.pr().equals(expected.pr())
                || !snapshot.cycleId().equals(expected.cycleId()) || !snapshot.headSha().equals(expected.headSha())
                || !snapshot.provider().equals(expected.evidenceProvider())) {
            return result(expected, version, UNKNOWN, INCOMPLETE_EVIDENCE);
        }
        var review = snapshot.sources().stream()
                .filter(s -> s.kind() == Kind.REVIEW && s.id().equals(completion.reviewId())).findFirst();
        if (review.isEmpty()) return result(expected, version, UNKNOWN, MISSING_REVIEW);
        Source source = review.orElseThrow();
        if (!expected.reviewer().equals(source.author()) || source.staleFor(expected.headSha())
                || !("APPROVED".equals(source.reviewState()) || "COMMENTED".equals(source.reviewState())
                    || "CHANGES_REQUESTED".equals(source.reviewState()))) {
            return result(expected, version, UNKNOWN, UNVERIFIED_REVIEW);
        }
        // All top-level inline candidates block, even outdated/resolved ones. Retained findings
        // missing from this snapshot must also be included in the caller's pending assessment.
        if (snapshot.sources().stream().anyMatch(s -> s.kind() == Kind.REVIEW_COMMENT && s.candidate()
                || s.feedback() == Feedback.ACTIONABLE)
                || "CHANGES_REQUESTED".equals(source.reviewState()) || completion.conclusion() == Conclusion.FINDINGS) {
            return result(expected, version, ACTION_REQUIRED, BLOCKING_FINDINGS);
        }
        if (snapshot.sources().stream().anyMatch(s -> s.feedback() == Feedback.NEEDS_HUMAN)
                || completion.conclusion() == Conclusion.UNSTRUCTURED) {
            return result(expected, version, NEEDS_HUMAN, AMBIGUOUS_FEEDBACK);
        }
        if (completion.conclusion() != Conclusion.NO_FINDINGS) return result(expected, version, UNKNOWN, UNKNOWN_CONCLUSION);
        // Future/contradictory source revisions cannot prove completion at this observation.
        if (snapshot.sources().stream().anyMatch(s -> s.updatedAt() != null && s.updatedAt().isAfter(snapshot.capturedAt()))) {
            return result(expected, version, UNKNOWN, CONTRADICTORY_EVIDENCE);
        }
        return result(expected, version, CLEAN_FOR_SHA, CORRELATED_NO_FINDINGS);
    }

    private ReviewOutcome result(Run run, long version, Verdict verdict, Reason reason) {
        return new ReviewOutcome(run, version, verdict, reason);
    }
}
