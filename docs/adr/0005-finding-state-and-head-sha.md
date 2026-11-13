# ADR 0005: Durable findings and per-head-SHA review evidence

**Status:** Accepted (implementation pending)  
**Date:** 2026-11-13

## Context

GitHub has resolvable inline threads and general comments without a Resolve button. A clean comment can become stale after a new commit.

## Decision

Normalize all actionable feedback as durable `Finding` records with provenance (provider, comment/thread/review), severity, disposition and originating SHA. General comments receive a local finding ID and explicit disposition mechanism. A resolved GitHub thread is only one signal and does not prove the code is fixed. Bind clean verdicts to reviewed head SHA; invalidate/refresh after relevant updates.

## Consequences

More robust state and audit, but requires comment classification and human overrides for ambiguous prose.

## Alternatives

Treat all comments as threads: misses general feedback. Accept resolved checkbox as fixed: unreliable. Treat all bot “LGTM” as permanent: allows stale approvals.

## Verification

General-only feedback; deleted/edited comment; no-finding review; thread resolved before code changed; force-push invalidating a previously clean result.
