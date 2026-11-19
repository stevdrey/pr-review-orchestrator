package io.github.stevdrey.prrevieworchestrator.domain;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;

/** Metadata for a single author notification. No source, findings, user prose or credentials. */
public final class AuthorNotice {
    private AuthorNotice() {}

    public record Job(long id, PullRequestId pr, String headSha, State state, long version, int attempt) {
        public Job {
            CommitSha.require(headSha);
            if (state != State.READY_FOR_HUMAN && state != State.ACTION_REQUIRED) {
                throw new IllegalArgumentException("Invalid notification state");
            }
        }
    }

    public enum ProviderEvidence { CODEX, RECORDED, UNAVAILABLE }
    public record Evidence(Ci ci, ProviderEvidence baseline, ProviderEvidence deep) {}
}
