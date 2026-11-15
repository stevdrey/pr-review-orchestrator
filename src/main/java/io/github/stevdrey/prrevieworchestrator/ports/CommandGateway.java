package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;

/** An adapter must explicitly support a command before any remote call. No arbitrary action strings.
 * Calls must have strict deadlines (total operation under 10 seconds). Reconcile must obtain the
 * authoritative PR head and durable command-key evidence. After ambiguous timeouts report UNKNOWN
 * until remote settlement is proved; lack of an eventually-consistent search result is not ABSENT.
 * execute must enforce remote idempotency and all action/approval/budget gates, including a fresh
 * SHA check at the side-effect boundary. A gateway without these capabilities must fail closed.
 */
public interface CommandGateway {
    boolean supports(Command command);
    RemoteState reconcile(Command command);
    void execute(Command command);
}
