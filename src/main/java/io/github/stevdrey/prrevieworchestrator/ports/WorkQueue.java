package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** All mutations are committed before returning. Stale/expired tokens cannot mutate or execute work. */
public interface WorkQueue {
    /** Upper bound on command intents one event may plan; larger plans are INVALID_EVENT. */
    int MAX_INTENTS = 32;

    Optional<Event> claimEvent();
    Optional<Command> claimCommand();
    Optional<Command> claimPlannedReview(PullRequestId pr);
    boolean completeEvent(Event event, List<Intent> intents, Runnable authorize);
    /** Lock the lease and authorization rows through reconciliation, effect and terminal commit. */
    boolean executeCommand(Command command, Runnable authorize, Supplier<Outcome> operation);
    /** Fence the outbox row through the request store's independent authorization and send transactions. */
    boolean executePlannedReview(Command command, Supplier<Outcome> operation);
    boolean fail(Lease lease, ErrorCode code, boolean retryable);
}
