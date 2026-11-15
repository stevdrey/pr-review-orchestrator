# Roadmap

**Status:** Initial implementation. The Java service bootstrap, PostgreSQL/Flyway schema and opt-in GitHub App authentication/installation authorization are present, with a local Compose database and integration tests. [App registration and rotation](github/app-setup.md) have an operator runbook; [live development registration and scoped-token verification](github/issue-11-registration-evidence.md) are recorded separately from local tests, with remote invalidation limitations. Issue #12 adds opt-in signed webhook ingress with authorized, deduplicated metadata acceptance; [configuration, routes and limits](github/webhook-ingress.md) are documented. Event dispatch remains planned. Issue trackers are the source of truth for implementation.

## Milestones

| Phase | Priority | Outcome | Exit condition |
| --- | --- | --- | --- |
| [EPIC 1](https://github.com/stevdrey/pr-review-orchestrator/issues/1) — Foundations & GitHub App | P0 | Java 27 / Spring Boot skeleton, Postgres/Flyway, verified GitHub webhooks, durable event inbox | Replay and unauthorized events do not cause duplicate/unauthorized actions |
| [EPIC 2](https://github.com/stevdrey/pr-review-orchestrator/issues/2) — Reviews & findings | P0 | Codex trigger spike, review/comment normalization, PR state machine | Inline and general findings tracked against current head SHA |
| [EPIC 3](https://github.com/stevdrey/pr-review-orchestrator/issues/3) — Feedback loop & notifications | P0 | Push / CI / resolution correlation, bounded retries, author notifications | Deterministic end-to-end review/repair/clean-review loop demonstrated |
| [EPIC 4](https://github.com/stevdrey/pr-review-orchestrator/issues/4) — MCP & Dot event bridge | P1 | Optional MCP tools, subscription-based event delivery and provider-neutral callbacks | Verified with a supported client; no polling dependency |
| [EPIC 5](https://github.com/stevdrey/pr-review-orchestrator/issues/5) — Policy, budgets & metrics | P1 | Per-installation rules, API usage accounting, quotas, audit and observability | Reliable per-PR and global budget enforcement and operator controls |
| [EPIC 6](https://github.com/stevdrey/pr-review-orchestrator/issues/6) — Deep review providers | P1 | Strong-model OpenAI review, optional Claude review, reusable skills | Validated prompts, normalized findings, error/fallback handling |
| [EPIC 7](https://github.com/stevdrey/pr-review-orchestrator/issues/7) — Sandboxed auto-fix | P2 | CI diagnosis, isolated workers, patches/tests, approval gates | No unreviewed privileges or runaway repair loops |
| [EPIC 8](https://github.com/stevdrey/pr-review-orchestrator/issues/8) — Operator UI & operations | P2 | Admin console, alerts, deployment, audit and retention controls | Maintainable, inspectable service with documented runbooks |

**Priority vocabulary:** P0 must-have MVP, P1 next increment, P2 later. Dependencies: 1 → 2 → 3; 4–6 build on 1–3; 7 requires 2, 3, 5; 8 can evolve incrementally.

## MVP vertical slice (first release candidate)

1. Verify a signed GitHub webhook and authorize installation/repository.
2. Persist event and process it idempotently.
3. Trigger a Codex review for a reviewable PR after explicit owner-token/identity validation.
4. Ingest inline threads **and general comments**, preserve provenance and commit SHA.
5. On a fix push, wait for policy gates and re-request review without unbounded loops.
6. If CI and both review stages are clear for the latest SHA, notify the author for human consideration.
7. Demonstrate negative-path tests (replay, tampered signature, stale SHA, provider timeout, budget exhaustion).

## Architecture spikes (must precede assumptions)

- Confirm whether a GitHub App user access token can request Codex in the **desired account and subscription billing context**; fallback to supported automatic reviews or a different review provider.
- Verify GitHub review-thread state event semantics and API permission boundaries.
- Verify **current** Dot/MCP event subscription support and access requirements in a real eligible client before relying on it. Keep core event orchestration independent.
- Validate OpenAI model names, pricing and any API credits with the actual account and live pricing before establishing production quotas.
- Decide how general comments are linked to tracked findings when the provider cannot produce a structured finding identifier.

## Definition of done

Every phase delivers documented behavior, secured boundaries, tests with real outcomes, updated ADRs, and verified operator visibility. A prototype or accepted ADR does not itself mean a feature is implemented.
