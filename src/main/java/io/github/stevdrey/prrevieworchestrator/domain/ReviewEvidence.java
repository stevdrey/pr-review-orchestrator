package io.github.stevdrey.prrevieworchestrator.domain;

import java.time.Instant;
import java.util.*;

/** Observations, never clean verdicts or verified dispositions. Bodies stay at the source provider. */
public final class ReviewEvidence {
    private ReviewEvidence() {}

    public enum Kind { REVIEW, REVIEW_COMMENT, REVIEW_THREAD, ISSUE_COMMENT }
    public enum Feedback { NONE, ACTIONABLE, NEEDS_HUMAN }
    public enum Severity { UNKNOWN, INFO, LOW, MEDIUM, HIGH, CRITICAL }

    public record Source(String id, Kind kind, String reviewId, String threadId, String replyToId,
            String author, String reviewedSha, String originalSha, String bodySha256,
            Instant updatedAt, String reviewState, Boolean resolved, Boolean outdated,
            String path, Integer line, Integer originalLine, Severity severity, Feedback feedback) {
        /** Compatibility constructor for inline evidence and conservative review observations. */
        public Source(String id, Kind kind, String reviewId, String threadId, String replyToId,
                String author, String reviewedSha, String originalSha, String bodySha256,
                Instant updatedAt, String reviewState, Boolean resolved, Boolean outdated,
                String path, Integer line, Integer originalLine, Severity severity) {
            this(id, kind, reviewId, threadId, replyToId, author, reviewedSha, originalSha, bodySha256,
                    updatedAt, reviewState, resolved, outdated, path, line, originalLine, severity,
                    kind == Kind.REVIEW || kind == Kind.ISSUE_COMMENT ? Feedback.NEEDS_HUMAN : Feedback.NONE);
        }
        public Source {
            text(id); Objects.requireNonNull(kind);
            Objects.requireNonNull(severity); Objects.requireNonNull(feedback);
            if (!general(kind) && feedback != Feedback.NONE) invalid();
            if (kind != Kind.REVIEW_THREAD) {
                // A null commit (e.g. after a force-push) is recorded as unknown and staleFor() treats it as stale.
                Objects.requireNonNull(updatedAt);
                if (reviewedSha != null) CommitSha.require(reviewedSha);
                if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}")) invalid();
            }
            if (originalSha != null) CommitSha.require(originalSha);
            for (String value : new String[] {reviewId, threadId, replyToId, author, path, reviewState}) {
                if (value != null) text(value);
            }
            if (line != null && line <= 0 || originalLine != null && originalLine <= 0) invalid();
            if (kind == Kind.REVIEW) {
                if (threadId != null || replyToId != null || resolved != null || outdated != null
                        || reviewState == null || !id.equals(reviewId) || severity != Severity.UNKNOWN) invalid();
            } else if (kind == Kind.ISSUE_COMMENT) {
                if (reviewId != null || threadId != null || replyToId != null || reviewedSha != null
                        || originalSha != null || reviewState != null || resolved != null || outdated != null
                        || path != null || line != null || originalLine != null) invalid();
            } else if (kind == Kind.REVIEW_COMMENT) {
                if (threadId == null || resolved == null || outdated == null) invalid();
            } else if (!id.equals(threadId) || reviewId != null || reviewedSha != null || originalSha != null
                    || updatedAt != null || bodySha256 != null || replyToId != null || author != null
                    || resolved == null || outdated == null || severity != Severity.UNKNOWN) invalid();
        }

        /** Replies are evidence attached to a conversation, not independent actionable findings. */
        public boolean candidate() { return kind == Kind.REVIEW_COMMENT && replyToId == null
                || general(kind) && feedback != Feedback.NONE; }
        public boolean general() { return general(kind); }
        private static boolean general(Kind kind) { return kind == Kind.REVIEW || kind == Kind.ISSUE_COMMENT; }
        public boolean staleFor(String headSha) { return reviewedSha == null || !reviewedSha.equals(headSha) || Boolean.TRUE.equals(outdated); }
    }

    public record Snapshot(UUID id, UUID cycleId, PullRequestId pr, String provider, String headSha,
            Instant capturedAt, List<Source> sources) {
        public Snapshot {
            Objects.requireNonNull(id); Objects.requireNonNull(cycleId); Objects.requireNonNull(pr);
            text(provider); CommitSha.require(headSha); Objects.requireNonNull(capturedAt);
            // PostgreSQL timestamps have microsecond precision; canonicalize before fingerprinting.
            capturedAt = capturedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            sources = sources.stream().sorted(Comparator.comparing(Source::id)).toList();
            if (sources.size() > 2000 || sources.stream().map(Source::id).distinct().count() != sources.size()) invalid();
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Review evidence conflict"); }
    }
    public static final class Unavailable extends RuntimeException {
        private final boolean retryable;

        /** @param retryable false when the snapshot or its data deterministically fails; retrying cannot help */
        public Unavailable(boolean retryable) {
            super("Review evidence unavailable");
            this.retryable = retryable;
        }

        public boolean retryable() { return retryable; }
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) invalid();
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid review evidence"); }
}
