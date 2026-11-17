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
        if (!reserved.created()) return reserved.state() == ReviewRequestStore.State.COMMENT_CREATED
                ? Result.DUPLICATE : Result.OUTCOME_UNKNOWN;
        store.send(request, reserved.id(), guards, () -> comments.create(request, actorId,
                () -> { authorization.requireAuthorized(request.pr().scope()); approval.requirePermitted(request); }));
        return Result.COMMENT_CREATED;
    }
}
