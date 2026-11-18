package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;

/** Trusted caller supplies verified event metadata, never comment instructions or provider prose. */
public record ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
        Trigger trigger, long senderId, boolean bot, long headEpoch) {
    public enum Trigger { PR_OPENED, PR_READY, PR_SYNCHRONIZE, PR_REOPENED, COMMENT, REVIEW }
    /** Epoch 0: no same-head confirmation has been journaled for this SHA. */
    public ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
            Trigger trigger, long senderId, boolean bot) {
        this(pr, headSha, stage, provider, trigger, senderId, bot, 0);
    }
    public ReviewRequest {
        Objects.requireNonNull(pr);
        Objects.requireNonNull(trigger);
        CommitSha.require(headSha);
        if (senderId <= 0 || headEpoch < 0 || stage == null || !stage.matches("[A-Z_]{1,32}")
                || provider == null || !provider.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid review request");
        }
    }
    public String key() {
        return pr.scope().installationId() + "/" + pr.scope().repositoryId() + "/" + pr.number()
                + "/" + headSha + "/" + stage + "/" + provider + (headEpoch == 0 ? "" : "/" + headEpoch);
    }
}
