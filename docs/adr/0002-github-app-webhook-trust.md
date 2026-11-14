# ADR 0002: GitHub App, explicit actor identity and signed webhook ingress

**Status:** Accepted (App authentication and local authorization implemented; webhook and user authorization pending)
**Date:** 2026-11-13

## Context

PR activity must trigger processing without periodic repository polling. Requests must not be attributed to an unrelated collaborator.

## Decision

Register a least-privilege GitHub App. Validate HMAC-SHA256 over raw webhook bytes, delivery identity, installation ID and allowed repository ID. Use installation-scoped tokens for app-level actions and an explicitly consented GitHub App user authorization flow only when a specific actor is required. **Before enabling a Codex request path, test separately** (1) GitHub comment actor attribution, (2) whether Codex accepts the trigger and (3) subscription/usage attribution.

## Consequences

Tokens must be rotated, encrypted and scoped. A GitHub comment author alone cannot establish who is billed for Codex. Unsupported identity behavior requires fallback to approved review mechanisms.

## Alternatives

Shared PAT: simpler but insecure and difficult to revoke. GitHub Actions-only: less centrally configurable. Polling: slower, wasteful and not the target trigger.

## Verification

Tampered-signature tests; foreign-installation rejection; explicit owner-auth test and observability record for Codex charging.
