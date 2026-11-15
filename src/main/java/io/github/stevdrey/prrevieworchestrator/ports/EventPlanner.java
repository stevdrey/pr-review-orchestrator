package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import java.util.List;

/** Read-only, bounded planning. Unknown/poison routes must throw a non-retryable Failure.
 * Resolve missing PR/SHA from authoritative state; no provider write or billable action here.
 */
public interface EventPlanner {
    List<Intent> plan(Event event);
}
