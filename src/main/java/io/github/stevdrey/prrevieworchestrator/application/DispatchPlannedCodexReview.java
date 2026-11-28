package io.github.stevdrey.prrevieworchestrator.application;

import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** A native GitHub comment uses the adapter's durable permit, not a retryable remote command. */
public final class DispatchPlannedCodexReview {
    private final WorkQueue queue;
    private final ReviewProvider provider;
    private final PullRequestId pr;
    private final long actorId;

    public DispatchPlannedCodexReview(WorkQueue queue, ReviewProvider provider, PullRequestId pr, long actorId) {
        if (actorId <= 0) throw new IllegalArgumentException("Planned Codex dispatch requires an authorized actor");
        this.queue = queue;
        this.provider = provider;
        this.pr = pr;
        this.actorId = actorId;
    }

    public boolean processCommand() {
        var claimed = queue.claimPlannedReview(pr);
        if (claimed.isEmpty()) return false;
        Command command = claimed.orElseThrow();
        var receiptKnown = new AtomicBoolean();
        try {
            if (queue.executePlannedReview(command, () -> {
                var outcome = dispatch(command);
                receiptKnown.set(outcome == Outcome.SUCCEEDED);
                return outcome;
            })) {
                DurableDispatcher.report(command.lease(), "COMPLETED");
            }
        } catch (RuntimeException failure) {
            var safe = receiptKnown.get() ? new Failure(ErrorCode.REMOTE_UNCERTAIN, true) : Failure.sanitized(failure);
            if (queue.fail(command.lease(), safe.code(), safe.retryable())) {
                DurableDispatcher.report(command.lease(), safe.code().name());
            }
        }
        return true;
    }

    private Outcome dispatch(Command command) {
        Intent intent = command.intent();
        if (!REQUEST_REVIEW.equals(intent.type()) || !"codex".equals(intent.provider())
                || !"BASELINE".equals(intent.stage()) || !pr.scope().equals(command.scope())
                || pr.number() != intent.prNumber()) {
            throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
        }
        // The consenting actor is the configured request principal. The inbox sender is not a grant.
        var request = new ReviewRequest(pr, intent.expectedHeadSha(), intent.stage(), intent.provider(),
                ReviewRequest.Trigger.PLANNED_REREVIEW, actorId, false, command.plannedHeadEpoch(), command.lease().id());
        try {
            return switch (provider.request(request)) {
                case COMMENT_CREATED, DUPLICATE -> Outcome.SUCCEEDED;
                case NOT_SENT, IGNORED -> Outcome.CANCELLED;
                case PENDING -> throw new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
                case OUTCOME_UNKNOWN -> throw new Failure(ErrorCode.REMOTE_UNCERTAIN, false);
            };
        } catch (ReviewRequestFailure failure) {
            // Transient codes leave the permit RESERVED in the store, so a bounded retry can still send it.
            return switch (failure.code()) {
                case STALE_HEAD, ACTOR_DENIED -> Outcome.CANCELLED;
                case POLICY_DENIED, LIMIT_REACHED, HUMAN_INTERVENTION_REQUIRED -> Outcome.POLICY_DENIED;
                case REMOTE_UNAVAILABLE, PR_NOT_READY, CAPABILITY_UNVERIFIED ->
                        throw new Failure(ErrorCode.REMOTE_UNAVAILABLE, true);
                case OUTCOME_UNKNOWN -> throw new Failure(ErrorCode.REMOTE_UNCERTAIN, false);
                case STORE_UNAVAILABLE, CI_NOT_PASSED -> throw new Failure(ErrorCode.INTERNAL_ERROR, true);
                case DISABLED, UNSUPPORTED_TRIGGER -> throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
            };
        } catch (AuthorizationDenied denied) {
            // A denied scope posts nothing; any receipt from an earlier attempt still occupies its iteration slot.
            return Outcome.CANCELLED;
        } catch (AuthorizationUnavailable unavailable) {
            throw new Failure(ErrorCode.AUTHORIZATION_UNAVAILABLE, true);
        }
    }
}
