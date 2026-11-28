package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.Objects;
import java.util.UUID;

/** Trusted caller supplies verified event or sandbox operator metadata. */
public record ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
        Trigger trigger, long senderId, boolean bot, long headEpoch, UUID plannedCommandId, UUID outboxCommandId) {
    public enum Trigger { PR_OPENED, PR_READY, PR_SYNCHRONIZE, PR_REOPENED, SANDBOX_OPERATOR, PLANNED_REREVIEW, COMMENT, REVIEW }

    public ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
            Trigger trigger, long senderId, boolean bot, long headEpoch, UUID plannedCommandId) {
        this(pr, headSha, stage, provider, trigger, senderId, bot, headEpoch, plannedCommandId, null);
    }

    public ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
            Trigger trigger, long senderId, boolean bot, long headEpoch) {
        this(pr, headSha, stage, provider, trigger, senderId, bot, headEpoch, null);
    }
    /** Epoch 0 denotes the initial visit to this SHA before any same-head confirmation. */
    public ReviewRequest(PullRequestId pr, String headSha, String stage, String provider,
            Trigger trigger, long senderId, boolean bot) {
        this(pr, headSha, stage, provider, trigger, senderId, bot, 0);
    }
    public ReviewRequest {
        Objects.requireNonNull(pr);
        Objects.requireNonNull(trigger);
        CommitSha.require(headSha);
        // A generic outbox command names itself so its own queued row is not treated as a duplicate.
        if ((plannedCommandId != null) != (trigger == Trigger.PLANNED_REREVIEW)
                || plannedCommandId != null && outboxCommandId != null
                || senderId <= 0 || headEpoch < 0 || stage == null || !stage.matches("[A-Z_]{1,32}")
                || provider == null || !provider.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid review request");
        }
    }
    public String key() {
        return pr.scope().installationId() + "/" + pr.scope().repositoryId() + "/" + pr.number()
                + "/" + headSha + "/" + stage + "/" + provider + (headEpoch == 0 ? "" : "/" + headEpoch);
    }
}
