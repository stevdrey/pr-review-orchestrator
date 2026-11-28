# ADR 0009 — Versioned repository review policy and local operator controls

**Status** Accepted for the local operator boundary

**Date** 2026-11-27

## Context

[Issue #27](https://github.com/stevdrey/pr-review-orchestrator/issues/27) needs repository-specific approval rules and a safe way to change them while review commands and provider calls are in flight. [ADR 0004](0004-deterministic-policy-and-budget-gates.md) requires deterministic gates independently of provider, model and budget integrations.

## Decision

Store append-only policy versions for each installation and repository pair. An unconfigured repository denies review requests. Queue entries and request reservations reference the exact scoped version that admitted them. Planned Codex requests inherit the version of their command. At send time the latest version can add restrictions while the pinned version and existing gates still apply.

Hold a shared repository lock from admission through commit and from the final send decision through its bounded effect and receipt. Policy edits take the exclusive lock and require the expected version. The PR lock continues to serialize head, CI and iteration decisions. A committed pause blocks subsequent sends. An edit waits for a bounded send already inside the fence.

Use a local, non-web operator command. Authenticate through PostgreSQL and intersect the login's scoped operator grant and actor ID with deployment configuration and live installation membership. Append actor, operation and server time with each successful version. Keep operator grant provisioning under database administration. The operator command starts without provider workers.

## Consequences

Existing installations must publish an explicit policy before starting new review actions. Historical commands and reservations retain their original keys and receipts. Work without version provenance can be reconciled where a receipt is available. It lacks permission for a fresh provider effect. Database administration controls grant provisioning, backups and attribution to people. No GitHub permissions, model input, provider secrets or provider charges are added by the operator command.

## Alternatives

Mutable repository rows would let a retry replace the rules and audit context for earlier work. Freezing the only policy check at enqueue would leave queued actions eligible after a pause. Reusing a PR-scoped MCP approval for repository-wide controls would expand the authority of that grant.

## Verification

Exercise default denial, wrong role and actor, concurrent edits, exact-version propagation, cap and CI changes, quiet-hour boundaries, queue cancellation and pause fencing in disposable PostgreSQL. See the [operator guide](../development/repository-review-policy.md) for provisioning, rollout and capability limits.
