package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewOutcome.*;
import java.util.Objects;
import java.util.Optional;

/**
 * A deployment-approved, bounded, authenticated lookup. The provider record or verifiable join must
 * bind the exact GitHub trigger to the uniquely current terminal execution and attempt, full SHA,
 * and exact GitHub review source revision. The adapter establishes the review author's provider identity.
 * Missing, conflicting, superseded or unauthenticated records return empty. There is no native implementation.
 */
@FunctionalInterface
public interface CodexCompletionSource {
    Optional<TerminalAttempt> terminalAttempt(PullRequestId pr, long triggerCommentId);

    record TerminalAttempt(CodexProvenance provenance, String reviewId, String reviewer,
            Status status, Conclusion conclusion) {
        public TerminalAttempt {
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(conclusion);
            if (status != Status.COMPLETED && status != Status.FAILED && status != Status.TIMED_OUT) {
                throw new IllegalArgumentException("Nonterminal Codex attempt");
            }
            for (String value : new String[] {reviewId, reviewer}) {
                if (value == null || value.isBlank() || value.length() > 1024) {
                    throw new IllegalArgumentException("Invalid Codex review identity");
                }
            }
        }
    }
}
