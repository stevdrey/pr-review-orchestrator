package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.*;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.*;

/** Internal user-comment adapter. The durable request owns the one-shot POST permit.
 * GitHub comments lack atomic SHA preconditions and remote idempotency keys.
 */
public final class CodexReviewAdapter implements ReviewProvider {
    public enum Mode { STANDARD, SANDBOX_OPERATOR, PLANNED }
    private final RepositoryAuthorization authorization;
    private final ReviewRequestStore store;
    private final CodexApproval approval;
    private final GitHubCodexComments comments;
    private final long actorId;
    private final int limit;
    private final Mode mode;

    public CodexReviewAdapter(RepositoryAuthorization authorization, ReviewRequestStore store,
            CodexApproval approval, GitHubCodexComments comments, long actorId, int limit) {
        this(authorization, store, approval, comments, actorId, limit, Mode.STANDARD);
    }

    public CodexReviewAdapter(RepositoryAuthorization authorization, ReviewRequestStore store,
            CodexApproval approval, GitHubCodexComments comments, long actorId, int limit, Mode mode) {
        if (actorId <= 0 || limit < 1 || limit > 6) throw new IllegalArgumentException("Invalid Codex policy");
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.store = java.util.Objects.requireNonNull(store);
        this.approval = java.util.Objects.requireNonNull(approval);
        this.comments = java.util.Objects.requireNonNull(comments);
        this.actorId = actorId;
        this.limit = limit;
        this.mode = java.util.Objects.requireNonNull(mode);
    }

    @Override public Result request(ReviewRequest request) {
        authorization.requireAuthorized(request.pr().scope());
        if (request.bot() || request.trigger() == ReviewRequest.Trigger.COMMENT
                || request.trigger() == ReviewRequest.Trigger.REVIEW) return Result.IGNORED;
        if (!request.provider().equals("codex") || !request.stage().equals("BASELINE")) {
            throw new ReviewRequestFailure(UNSUPPORTED_TRIGGER);
        }
        boolean planned = request.trigger() == ReviewRequest.Trigger.PLANNED_REREVIEW;
        if (planned != (mode == Mode.PLANNED) || planned && request.senderId() != actorId) {
            throw new ReviewRequestFailure(UNSUPPORTED_TRIGGER);
        }
        if (request.trigger() == ReviewRequest.Trigger.SANDBOX_OPERATOR
                && (mode != Mode.SANDBOX_OPERATOR || request.senderId() != actorId)) {
            throw new ReviewRequestFailure(UNSUPPORTED_TRIGGER);
        }
        Runnable authorize = () -> authorization.requireAuthorized(request.pr().scope());
        Runnable approve = () -> approval.requirePermitted(request);
        var reserved = store.reserve(request, actorId, limit, authorize, approve);
        if (!reserved.created()) return switch (reserved.state()) {
            case RESERVED -> Result.PENDING;
            case ATTEMPTED -> Result.OUTCOME_UNKNOWN;
            case NOT_SENT -> Result.NOT_SENT;
            case COMMENT_CREATED, COMMENT_CREATED_HEAD_UNCONFIRMED -> Result.DUPLICATE;
        };
        Runnable guards = () -> {
            authorize.run();
            approve.run();
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
