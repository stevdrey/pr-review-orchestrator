package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.*;

/** Internal user-comment adapter. Never registered as a durable CommandGateway: GitHub comments
 * lack atomic SHA preconditions and remote idempotency keys. RESERVED requires human reconciliation.
 */
public final class CodexReviewAdapter implements ReviewProvider {
    private final RepositoryAuthorization authorization;
    private final ReviewRequestStore store;
    private final CodexApproval approval;
    private final GitHubCodexComments comments;
    private final long actorId;
    private final int limit;

    public CodexReviewAdapter(RepositoryAuthorization authorization, ReviewRequestStore store,
            CodexApproval approval, GitHubCodexComments comments, long actorId, int limit) {
        if (actorId <= 0 || limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid Codex policy");
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.store = java.util.Objects.requireNonNull(store);
        this.approval = java.util.Objects.requireNonNull(approval);
        this.comments = java.util.Objects.requireNonNull(comments);
        this.actorId = actorId;
        this.limit = limit;
    }

    @Override public Result request(ReviewRequest request) {
        authorization.requireAuthorized(request.pr().scope());
        if (request.bot() || request.trigger() == ReviewRequest.Trigger.COMMENT
                || request.trigger() == ReviewRequest.Trigger.REVIEW) return Result.IGNORED;
        if (!request.provider().equals("codex") || !request.stage().equals("BASELINE")) {
            throw new ReviewRequestFailure(UNSUPPORTED_TRIGGER);
        }
        Runnable guards = () -> {
            authorization.requireAuthorized(request.pr().scope());
            approval.requirePermitted(request);
        };
        var reserved = store.reserve(request, actorId, limit, guards);
        if (!reserved.created()) return switch (reserved.state()) {
            case RESERVED -> Result.PENDING;
            case ATTEMPTED -> Result.OUTCOME_UNKNOWN;
            case NOT_SENT -> Result.NOT_SENT;
            case COMMENT_CREATED, COMMENT_CREATED_HEAD_UNCONFIRMED -> Result.DUPLICATE;
        };
        try {
            store.send(request, reserved.id(), guards, () -> comments.create(request, actorId, guards));
        } catch (ReviewRequestFailure failure) {
            // Uncertain outcomes share one channel with redelivery; other codes are actionable and unsent.
            if (failure.code() == OUTCOME_UNKNOWN) return Result.OUTCOME_UNKNOWN;
            throw failure;
        }
        return Result.COMMENT_CREATED;
    }
}
