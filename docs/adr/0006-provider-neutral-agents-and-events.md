# ADR 0006: Provider-neutral review and event/agent adapters

**Status:** Accepted (internal review request port, default-disabled Codex adapter and conservative outcome classification implemented; durable PR events and opt-in signed webhook delivery implemented; live completion and Work/Dot adapters pending)
**Date:** 2026-11-13

## Context

Codex, Claude, OpenAI Responses, ChatGPT Dots/Work and MCP may offer different integration contracts and account entitlements.

## Decision

Define narrow `ReviewProvider`, `FindingNormalizer`, `AgentTaskExecutor`, `EventPublisher` and `NotificationPort` interfaces. A deterministic core works without any Dot. Optional MCP tools and push-event subscriptions may bridge supported ChatGPT clients after compatibility verification. Models never obtain unsupervised privileged GitHub actions. Every invocation carries policy/cost context and expected head SHA.

## Consequences

Flexible integration and graceful degradation, but adapter-specific capability checks and contract tests are required.

## Alternatives

Hardcode Codex or Claude integration into the state machine; brittle and provider-locked. Make Dot the only orchestrator; unavailable entitlements would prevent core workflows.

## Verification

Two interchangeable review adapters with identical normalized outcomes; Dot integration fallback; missing provider credentials; tool-injection and budget override tests.

Issue #25 implements the [domain event and publisher boundary](../development/domain-events.md). PostgreSQL records typed transition events and scoped delivery rows with the journal update. The opt-in HTTPS adapter uses configured destinations, per-subscription signatures, durable retries and send-time revocation checks. The Work/Dot spike verified a synthetic provider path. Production MCP subscription management stays separate from the local queue.
