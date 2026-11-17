package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;

/** Trusted deployment capability/policy boundary, not a user-supplied flag.
 * Must verify current consent, trigger support, entitlement AND individual accounting, and reserve
 * enforceable per-PR/installation/global budget by request.key(). Repeated calls must be idempotent.
 * Revalidate approval, holds, CI/findings/no-progress policy and budget before the actual effect.
 * Missing evidence must throw ReviewRequestFailure(CAPABILITY_UNVERIFIED or POLICY_DENIED).
 * No implementation ships while issue #42 accounting remains unresolved. Calls must be bounded.
 */
public interface CodexApproval {
    void requirePermitted(ReviewRequest request);
}
