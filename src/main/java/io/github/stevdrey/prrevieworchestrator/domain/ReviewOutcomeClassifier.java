package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Reason.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Verdict.*;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.util.Objects;
import java.util.Set;

/** Pure policy. No body text, keyword matcher, resolution shortcut or model decision is authoritative. */
public final class ReviewOutcomeClassifier {
    public ReviewOutcome classify(Run expected, long version, Completion completion,
            Snapshot snapshot, PendingFindings pending) {
        return classify(expected, version, completion, snapshot, pending, Set.of(), Set.of());
    }

    /**
     * Dismissed IDs must come from audited decisions for the exact snapshot revisions and current head.
     * Closed inline IDs must come from retained findings (DISMISSED/VERIFIED_FIXED) linked to this exact snapshot.
     */
    public ReviewOutcome classify(Run expected, long version, Completion completion, Snapshot snapshot,
            PendingFindings pending, Set<String> dismissedGeneralSources, Set<String> closedInlineSources) {
        Objects.requireNonNull(expected); Objects.requireNonNull(pending);
        Objects.requireNonNull(dismissedGeneralSources); Objects.requireNonNull(closedInlineSources);
        if (version < 0 || version == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid state version");
        if (pending == PendingFindings.ACTIONABLE) return result(expected, version, ACTION_REQUIRED, BLOCKING_FINDINGS);
        if (pending == PendingFindings.UNKNOWN) return result(expected, version, UNKNOWN, INCOMPLETE_EVIDENCE);
        if (completion == null) return unavailable(expected, version, pending, MISSING_COMPLETION);
        if (!expected.equals(completion.run())) return unavailable(expected, version, pending, UNCORRELATED_COMPLETION);
        if (completion.status() != Status.COMPLETED) return unavailable(expected, version, pending, PROVIDER_NOT_COMPLETED);
        if (snapshot == null || !snapshot.pr().equals(expected.pr())
                || !snapshot.cycleId().equals(expected.cycleId()) || !snapshot.headSha().equals(expected.headSha())
                || !snapshot.observedHeadSha().equals(expected.headSha())
                || !snapshot.provider().equals(expected.evidenceProvider())) {
            return unavailable(expected, version, pending, INCOMPLETE_EVIDENCE);
        }
        var review = snapshot.sources().stream()
                .filter(s -> s.kind() == Kind.REVIEW && s.id().equals(completion.reviewId())).findFirst();
        if (review.isEmpty()) return unavailable(expected, version, pending, MISSING_REVIEW);
        Source source = review.orElseThrow();
        if (!expected.reviewer().equals(source.author()) || source.staleFor(expected.headSha())
                || !("APPROVED".equals(source.reviewState()) || "COMMENTED".equals(source.reviewState())
                    || "CHANGES_REQUESTED".equals(source.reviewState()))) {
            return unavailable(expected, version, pending, UNVERIFIED_REVIEW);
        }
        // Inline candidates clear only through their retained finding status, never through general dismissals.
        // A completion whose own body is actionable contradicts NO_FINDINGS, so dismissing it cannot clear it;
        // its ambiguous (NEEDS_HUMAN) body can be dismissed like any other general feedback.
        var candidates = snapshot.sources().stream().filter(Source::candidate)
                .filter(s -> s.general()
                        ? s.id().equals(completion.reviewId()) && s.feedback() == Feedback.ACTIONABLE
                            || !dismissedGeneralSources.contains(s.id())
                        : !closedInlineSources.contains(s.id())).toList();
        if (candidates.stream().anyMatch(s -> !s.general() || s.feedback() == Feedback.ACTIONABLE)
                || "CHANGES_REQUESTED".equals(source.reviewState()) || completion.conclusion() == Conclusion.FINDINGS) {
            return result(expected, version, ACTION_REQUIRED, BLOCKING_FINDINGS);
        }
        if (pending == PendingFindings.NEEDS_HUMAN || !candidates.isEmpty()
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

    private ReviewOutcome unavailable(Run expected, long version, PendingFindings pending, Reason reason) {
        return pending == PendingFindings.NEEDS_HUMAN
                ? result(expected, version, NEEDS_HUMAN, AMBIGUOUS_FEEDBACK)
                : result(expected, version, UNKNOWN, reason);
    }

    private ReviewOutcome result(Run run, long version, Verdict verdict, Reason reason) {
        return new ReviewOutcome(run, version, verdict, reason);
    }
}
