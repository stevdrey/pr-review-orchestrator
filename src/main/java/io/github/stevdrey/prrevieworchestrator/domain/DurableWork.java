package io.github.stevdrey.prrevieworchestrator.domain;

import java.time.Duration;
import java.util.UUID;

/** Metadata-only work contracts. Provider text and credentials must never enter these records. */
public final class DurableWork {
    private DurableWork() {}

    public static final String REQUEST_REVIEW = "REQUEST_REVIEW";
    public static final String REVIEW_ASSESSMENT_EVENT = "sandbox_review_assessment";

    public enum Queue { INBOX, OUTBOX }
    public enum ErrorCode {
        INTERNAL_ERROR, AUTHORIZATION_DENIED, AUTHORIZATION_UNAVAILABLE, INVALID_EVENT, UNSUPPORTED_COMMAND,
        REMOTE_UNAVAILABLE, REMOTE_UNCERTAIN, LEASE_EXHAUSTED
    }
    public enum Outcome { SUCCEEDED, CANCELLED }
    public enum RemoteEffect { ABSENT, APPLIED, UNKNOWN }

    public record Lease(Queue queue, UUID id, UUID token, UUID correlationId, int attempt) {}
    public record Event(Lease lease, RepositoryScope scope, String eventType, String action,
            Integer prNumber, String observedHeadSha) {}
    public record Intent(int prNumber, String expectedHeadSha, String stage, String provider, String type) {
        public Intent {
            if (prNumber <= 0 || !CommitSha.isValid(expectedHeadSha)) {
                throw new IllegalArgumentException("Invalid command identity");
            }
            for (String value : new String[] {stage, provider, type}) {
                if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("Invalid command identity");
                }
            }
        }

        public String key(RepositoryScope scope) {
            return scope.installationId() + "/" + scope.repositoryId() + "/" + prNumber + "/"
                    + expectedHeadSha + "/" + stage + "/" + provider + "/" + type;
        }
    }
    public record Command(Lease lease, RepositoryScope scope, Intent intent, String key, Long plannedHeadEpoch) {
        public Command(Lease lease, RepositoryScope scope, Intent intent, String key) {
            this(lease, scope, intent, key, null);
        }
    }
    public record RemoteState(String currentHeadSha, RemoteEffect effect) {
        public RemoteState {
            if (!CommitSha.isValid(currentHeadSha) || effect == null) {
                throw new IllegalArgumentException("Invalid remote state");
            }
        }
    }

    public record Limits(Duration lease, Duration initialBackoff, Duration maxBackoff) {
        public Limits {
            if (lease == null || initialBackoff == null || maxBackoff == null
                    || lease.compareTo(Duration.ofSeconds(15)) < 0 || lease.compareTo(Duration.ofMinutes(5)) > 0
                    || initialBackoff.compareTo(Duration.ofSeconds(1)) < 0
                    || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("Invalid worker limits");
            }
        }

        public Duration backoff(int attempt) {
            long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
            return Duration.ofMillis(Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() * multiplier));
        }
    }

    /** Only stable codes cross the worker boundary; never propagate provider error bodies. */
    public static final class Failure extends RuntimeException {
        private final ErrorCode code;
        private final boolean retryable;

        public Failure(ErrorCode code, boolean retryable) {
            super(code.name());
            this.code = code;
            this.retryable = retryable;
        }
        public ErrorCode code() { return code; }
        public boolean retryable() { return retryable; }

        /** Copy only safe metadata, dropping causes/suppressed exceptions even from a known Failure. */
        public static Failure sanitized(RuntimeException failure) {
            if (failure instanceof Failure known) return new Failure(known.code(), known.retryable());
            if (failure instanceof AuthorizationUnavailable) return new Failure(ErrorCode.AUTHORIZATION_UNAVAILABLE, true);
            if (failure instanceof AuthorizationDenied) return new Failure(ErrorCode.AUTHORIZATION_DENIED, false);
            // Any other SecurityException (TLS, signing, adapter code) is not a membership denial.
            return new Failure(ErrorCode.INTERNAL_ERROR, true);
        }
    }
}
