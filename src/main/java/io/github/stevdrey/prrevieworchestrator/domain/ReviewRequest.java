package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;

/** Trusted caller supplies verified event metadata, never comment instructions or provider prose. */
public record ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
        Trigger trigger, long senderId, boolean bot) {
    public enum Trigger { PR_OPENED, PR_READY, PR_SYNCHRONIZE, PR_REOPENED, COMMENT, REVIEW }
    public ReviewRequest {
        Objects.requireNonNull(pr);
        Objects.requireNonNull(trigger);
        CommitSha.require(headSha);
        if (senderId <= 0 || stage == null || !stage.matches("[A-Z_]{1,32}")
                || provider == null || !provider.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid review request");
        }
    }
    public String key() {
        return pr.scope().installationId() + "/" + pr.scope().repositoryId() + "/" + pr.number()
                + "/" + headSha + "/" + stage + "/" + provider;
    }
}
