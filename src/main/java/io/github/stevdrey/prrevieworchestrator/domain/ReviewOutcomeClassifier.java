package io.github.stevdrey.prrevieworchestrator.domain;

import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Reason.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.Verdict.*;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
        if (!matchesSnapshot(expected, snapshot)) {
            return unavailable(expected, version, pending, INCOMPLETE_EVIDENCE);
        }
        var review = review(completion, snapshot);
        if (review.isEmpty()) return unavailable(expected, version, pending, MISSING_REVIEW);
        Source source = review.orElseThrow();
        if (!verifiedReview(expected, source)) {
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
        if (futureRevision(snapshot)) {
            return result(expected, version, UNKNOWN, CONTRADICTORY_EVIDENCE);
        }
        return result(expected, version, CLEAN_FOR_SHA, CORRELATED_NO_FINDINGS);
    }

    /** Fingerprint only the authenticated review's current actionable findings. Ignore IDs and shifted lines. */
    public Optional<String> findingFingerprint(Run expected, Completion completion, Snapshot snapshot,
            Set<String> dismissedGeneralSources, Set<String> closedInlineSources) {
        if (completion == null || !expected.equals(completion.run()) || completion.status() != Status.COMPLETED
                || completion.conclusion() != Conclusion.FINDINGS || !matchesSnapshot(expected, snapshot)
                || futureRevision(snapshot)
                || review(completion, snapshot).filter(source -> verifiedReview(expected, source)).isEmpty()) {
            return Optional.empty();
        }
        var findings = snapshot.sources().stream().filter(Source::candidate)
                .filter(source -> completion.reviewId().equals(source.reviewId())
                        && expected.reviewer().equals(source.author()) && !source.staleFor(expected.headSha()))
                .filter(source -> source.general()
                        ? source.feedback() == Feedback.ACTIONABLE && !dismissedGeneralSources.contains(source.id())
                        : !closedInlineSources.contains(source.id()))
                .map(source -> source.kind().name() + "/" + source.bodySha256() + "/"
                        + Sha256.hex(Objects.toString(source.path(), "").getBytes(StandardCharsets.UTF_8)))
                .sorted().toList();
        return findings.isEmpty() ? Optional.empty()
                : Optional.of(Sha256.hex(String.join("\n", findings).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean matchesSnapshot(Run expected, Snapshot snapshot) {
        return snapshot != null && snapshot.pr().equals(expected.pr()) && snapshot.cycleId().equals(expected.cycleId())
                && snapshot.headSha().equals(expected.headSha()) && snapshot.observedHeadSha().equals(expected.headSha())
                && snapshot.provider().equals(expected.evidenceProvider());
    }

    private Optional<Source> review(Completion completion, Snapshot snapshot) {
        return snapshot.sources().stream()
                .filter(source -> source.kind() == Kind.REVIEW && source.id().equals(completion.reviewId())).findFirst();
    }

    private boolean verifiedReview(Run expected, Source source) {
        return expected.reviewer().equals(source.author()) && !source.staleFor(expected.headSha())
                && ("APPROVED".equals(source.reviewState()) || "COMMENTED".equals(source.reviewState())
                    || "CHANGES_REQUESTED".equals(source.reviewState()));
    }

    private boolean futureRevision(Snapshot snapshot) {
        return snapshot.sources().stream().anyMatch(s -> s.updatedAt() != null && s.updatedAt().isAfter(snapshot.capturedAt()));
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
