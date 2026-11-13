# ADR 0004: Deterministic policy before AI decisions, with hard budgets

**Status:** Accepted (implementation pending)  
**Date:** 2026-11-13

## Context

Autonomous review and fix loops can generate runaway model/API charges and unsafe side effects.

## Decision

Use explicit Java policy gates for authorization, current head SHA, CI requirements, unresolved findings, no-progress detection, allowed actions, quotas and human approval. Initial **proposal**: maximum six automatic review iterations per PR, configurable before deployment. Reserve budget before billable operations and reconcile measured usage afterward; enforce per-PR/per-installation/global limits. Optional AI-based Decisions classification is advisory and cannot override the gate.

## Consequences

Deterministic and testable safety; slightly less flexible than unconstrained agent autonomy. Model/API pricing, organization credits and entitlement must be verified per environment, not hardcoded.

## Alternatives

Let model decide all actions: vulnerable to prompt injection, unpredictable cost. Cron and fixed retry counts without persistence: duplicate charges and poor observability.

## Verification

Race tests on budget reservations; hard-cap bypass attempts; no-progress stop; model requesting forbidden action; missing usage metadata must fail closed or pause.
