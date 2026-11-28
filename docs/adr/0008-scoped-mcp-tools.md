# ADR 0008: Scoped MCP tool sessions and local command approval

**Status:** Accepted for private deployments with operator-managed grants.

**Date:** 2026-11-27

## Context

[Issue #26](https://github.com/stevdrey/pr-review-orchestrator/issues/26) adds PR status and finding inspection, plus policy-gated commands. A client-controlled tool call cannot establish user authority, and the existing review adapters require independent provider identity, eligibility and budget controls.

## Decision

Expose opt-in Streamable HTTP for native clients. Authenticate every request with an expiring, revocable opaque bearer grant, provisioned by a trusted operator for one verified actor and PR. Intersect it with the deployment allowlist and live installation membership. Treat the full expected head and state version as preconditions. Require a server-side approval bound to the command, UUID, SHA and version for writes.

Filter findings to bounded, redacted metadata for the locally reconciled head. Reuse the existing PR lock and pause transition. Use the transition UUID for replay protection and persist actor and outcome audit metadata in the same transaction. Return a capability error for native review dispatch until an approved MCP integration can enforce the existing credential, approval and budget requirements.

## Consequences

The tool service requires no GitHub credential and adds no provider calls. Operators own grant provisioning and revocation when GitHub access changes. The bearer is sensitive while active. Use private ingress, bounded session lifetimes and header redaction. The service remains usable without a compatible Work or Dot client.

## Alternatives

Share a deployment-wide API key, which would make the called repository and actor claims client-controlled. Add OAuth and live permission reconciliation to the first tool deployment, which would require a separately verified client and identity integration.

## Verification

Use HTTP contract and abuse-case tests on PostgreSQL for scope, expiry, missing or stale SHA, mismatched approvals, concurrent replay and audit rollback. Keep the route disabled by default. Record provider and client limits in the [operator guide](../development/mcp-tools.md).
