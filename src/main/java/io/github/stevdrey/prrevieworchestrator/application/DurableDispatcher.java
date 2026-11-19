package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded single-item ticks, usable by a scheduler or deterministic operator/test runner. */
public final class DurableDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(DurableDispatcher.class);
    private final WorkQueue queue;
    private final RepositoryAuthorization authorization;
    private final Predicate<Event> inbox;
    private final Optional<CommandGateway> gateway;

    public DurableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            EventPlanner planner, CommandGateway gateway) {
        this(queue, authorization, event -> queue.completeEvent(event, List.copyOf(planner.plan(event)),
                () -> authorization.requireAuthorized(event.scope())), Optional.of(gateway));
    }

    /** Run the reconciler on the inbox. This mode never claims external commands. */
    public DurableDispatcher(WorkQueue queue, RepositoryAuthorization authorization, SandboxCiReconciler reconciler) {
        this(queue, authorization, reconciler::complete, Optional.empty());
    }

    private DurableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            Predicate<Event> inbox, Optional<CommandGateway> gateway) {
        this.queue = queue;
        this.authorization = authorization;
        this.inbox = inbox;
        this.gateway = gateway;
    }

    public boolean processEvent() {
        var claimed = queue.claimEvent();
        if (claimed.isEmpty()) return false;
        Event event = claimed.get();
        try {
            // Installation-level/legacy rows name no repository: an unsupported route, not a denial.
            if (event.scope() == null) throw new Failure(ErrorCode.INVALID_EVENT, false);
            authorization.requireAuthorized(event.scope());
            if (inbox.test(event)) {
                report(event.lease(), "PROCESSED");
            }
        } catch (RuntimeException failure) {
            handle(event.lease(), failure);
        }
        return true;
    }

    public boolean processCommand() {
        if (gateway.isEmpty()) return false;
        var commands = gateway.orElseThrow();
        var claimed = queue.claimCommand();
        if (claimed.isEmpty()) return false;
        Command command = claimed.get();
        var effectAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if (queue.executeCommand(command, () -> authorization.requireAuthorized(command.scope()), () -> {
                if (!commands.supports(command)) throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
                RemoteState state = commands.reconcile(command);
                // A later PR head does not undo a confirmed effect for this command's original SHA/key.
                if (state.effect() == RemoteEffect.APPLIED) return Outcome.SUCCEEDED;
                // A stale head forbids new writes, but cannot settle an earlier ambiguous effect.
                if (state.effect() == RemoteEffect.UNKNOWN) throw new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
                if (!command.intent().expectedHeadSha().equals(state.currentHeadSha())) return Outcome.CANCELLED;
                // No second authorization query: authorize.run() holds FOR SHARE locks on the
                // installation/repository rows, so a revocation cannot commit before this transaction ends.
                effectAttempted.set(true);
                commands.execute(command);
                return Outcome.SUCCEEDED;
            })) report(command.lease(), "COMPLETED");
        } catch (RuntimeException failure) {
            Failure safe = Failure.sanitized(failure);
            // Once execute has started, the effect may have landed even if the call or commit failed.
            // Record that ambiguity so the next attempt reconciles, and so a final-attempt dead letter is
            // marked REMOTE_UNCERTAIN (reconcile before any redrive) rather than a plain failure.
            if (effectAttempted.get() && safe.retryable()) safe = new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
            handle(command.lease(), safe);
        }
        return true;
    }

    private void handle(Lease lease, RuntimeException failure) {
        Failure safe = Failure.sanitized(failure);
        if (queue.fail(lease, safe.code(), safe.retryable())) report(lease, safe.code().name());
    }

    private static void report(Lease lease, String outcome) {
        LOG.info("queue={} work_id={} correlation_id={} attempt={} outcome={}",
                lease.queue(), lease.id(), lease.correlationId(), lease.attempt(), outcome);
    }
}
