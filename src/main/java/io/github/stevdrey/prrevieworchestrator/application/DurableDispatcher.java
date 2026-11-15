package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded single-item ticks, usable by a scheduler or deterministic operator/test runner. */
public final class DurableDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(DurableDispatcher.class);
    private final WorkQueue queue;
    private final RepositoryAuthorization authorization;
    private final EventPlanner planner;
    private final CommandGateway gateway;

    public DurableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            EventPlanner planner, CommandGateway gateway) {
        this.queue = queue;
        this.authorization = authorization;
        this.planner = planner;
        this.gateway = gateway;
    }

    public boolean processEvent() {
        var claimed = queue.claimEvent();
        if (claimed.isEmpty()) return false;
        Event event = claimed.get();
        try {
            authorization.requireAuthorized(event.scope());
            var intents = java.util.List.copyOf(planner.plan(event));
            if (intents.size() > 32) throw new Failure(ErrorCode.INVALID_EVENT, false);
            if (queue.completeEvent(event, intents, () -> authorization.requireAuthorized(event.scope()))) {
                report(event.lease(), "PROCESSED");
            }
        } catch (RuntimeException failure) {
            handle(event.lease(), failure);
        }
        return true;
    }

    public boolean processCommand() {
        var claimed = queue.claimCommand();
        if (claimed.isEmpty()) return false;
        Command command = claimed.get();
        try {
            if (queue.executeCommand(command, () -> authorization.requireAuthorized(command.scope()), () -> {
                if (!gateway.supports(command)) throw new Failure(ErrorCode.UNSUPPORTED_COMMAND, false);
                RemoteState state = gateway.reconcile(command);
                if (!command.intent().expectedHeadSha().equals(state.currentHeadSha())) return Outcome.CANCELLED;
                if (state.effect() == RemoteEffect.APPLIED) return Outcome.SUCCEEDED;
                if (state.effect() == RemoteEffect.UNKNOWN) throw new Failure(ErrorCode.REMOTE_UNCERTAIN, true);
                authorization.requireAuthorized(command.scope());
                gateway.execute(command);
                return Outcome.SUCCEEDED;
            })) report(command.lease(), "COMPLETED");
        } catch (RuntimeException failure) {
            handle(command.lease(), failure);
        }
        return true;
    }

    private void handle(Lease lease, RuntimeException failure) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        boolean retryable = true;
        if (failure instanceof SecurityException) {
            code = ErrorCode.AUTHORIZATION_DENIED;
            retryable = false;
        } else if (failure instanceof Failure known) {
            code = known.code();
            retryable = known.retryable();
        }
        if (queue.fail(lease, code, retryable)) report(lease, code.name());
    }

    private static void report(Lease lease, String outcome) {
        LOG.info("queue={} work_id={} correlation_id={} attempt={} outcome={}",
                lease.queue(), lease.id(), lease.correlationId(), lease.attempt(), outcome);
    }
}
