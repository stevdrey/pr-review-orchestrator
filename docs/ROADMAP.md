# Roadmap

**Status:** Initial implementation. The Java service bootstrap, PostgreSQL/Flyway schema and opt-in GitHub App authentication/installation authorization are present, with a local Compose database and integration tests. [App registration and rotation](github/app-setup.md) have an operator runbook; [live development registration and scoped-token verification](github/issue-11-registration-evidence.md) are recorded separately from local tests, with remote invalidation limitations. Issue #12 adds opt-in signed webhook ingress with authorized, deduplicated metadata acceptance; [configuration, routes and limits](github/webhook-ingress.md) are documented. Issue #13 adds [durable dispatch infrastructure](development/durable-dispatch.md): transactional command deduplication, fenced leases, bounded retries, SHA/remote-outcome reconciliation gates and dead-letter/correlation visibility. Ticks remain disabled until approved event-planning and command adapters exist; live review orchestration remains planned. Issue trackers are the source of truth for implementation.

Issue #15 implements the [internal persistent PR review state machine](development/review-state-machine.md): explicit guards, per-PR optimistic versions, transactional transition deduplication, pause/resume and clean-evidence invalidation on every head change. It does not enable live planning, provider reviews, budget accounting or notifications. Finding normalization is implemented separately by issue #16.

Issue #16 implements [GitHub review/thread snapshot normalization and durable ingestion](development/review-evidence.md), including immutable provenance metadata, explicit severity and stable inline candidate identities across review rounds. Issue #23 adds opt-in [durable reconciliation for one pinned sandbox PR](development/review-evidence.md#durable-sandbox-worker) and an [internal sandbox verifier](development/fix-correlation.md#verify-an-inline-fix-in-the-sandbox) for an independently checked correction. Resolution is retained as a signal. Production review orchestration remains pending.

Issue #17 adds [internal general-feedback tracking and audited dispositions](development/general-feedback.md): complete issue-comment/review-body capture, stable local IDs, conservative human escalation, commit-bearing fix claims and deterministic blocking of clean-stage transitions. New pending feedback revokes existing readiness, including while paused. Live author authentication/action endpoints, verified-fix processing and semantic prose classification remain future integrations.

Issue #18 implements the [internal Codex user-comment request adapter](development/codex-review-requests.md): scoped persistent deduplication, one-shot send permits, comment/bot loop filtering, actor/PR/SHA checks and a lifetime request ceiling. It remains disabled by default. Approved user OAuth and capability/accounting/budget implementations are required and are not supplied; issue #42's accepted limitation does not enable dispatch. Live provider acceptance and event-to-request integration remain unverified.

## Milestones

Issue #19 adds [internal conservative review-outcome classification](development/review-outcomes.md) and version-bound clean-stage recording. Unknown/ambiguous results cannot create clean transitions; completed structured results require exact PR/request/cycle/provider/stage/SHA correlation and no pending feedback. The live Codex completion contract, authoritative input gathering and event integration remain unverified; the request adapter stays disabled by default.

Issue #20 adds [internal fix correlation](development/fix-correlation.md). After a quiet period, authoritative changes to the current head can yield one durable re-review candidate for retained findings with the required dispositions.

Issue #21 adds [internal CI reconciliation and automatic review limits](development/ci-and-iteration-policy.md). Re-review and readiness depend on current-head CI, and exhausted iteration slots or repeated identical findings require human intervention.

Issue #22 adds [opt-in GitHub author notifications](development/author-notifications.md) on ready-for-human and action-required transitions, with a durable send permit per state, recipient and head SHA.

Issue #23 adds [local sandbox feedback-loop acceptance coverage and live prerequisites](architecture/testing-strategy.md#end-to-end-acceptance), including replay protection, current-SHA review and CI evidence, bounded stops and one author notice.

Issue #72 adds [durable sandbox re-review planning](development/synchronize-review-planning.md) from signed synchronize and CI deliveries, using authoritative PR reads, quiet-period coalescing and a deduplicated outbox intent for the eligible head.

Issue #74 connects [planned sandbox intents to the Codex request adapter](development/synchronize-review-planning.md#opt-in-dispatch-to-codex) and adds [exact terminal-attempt correlation](development/review-outcomes.md#authenticated-codex-completion) for an approved source. Local tests use a loopback GitHub and a signed completion fixture. Live dispatch stays disabled by default, and native completion for the exact request remains unverified.

Issue #26 adds [private MCP tools](development/mcp-tools.md) for redacted PR status and findings, plus an audited local pause under a scoped, expiring command grant. Native review dispatch and Work/Dot compatibility for this endpoint remain pending.

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
- [Issue #42 accounting investigation](spikes/issue-42-review-accounting.md) concluded with an owner-accepted limitation: +4 review activity and +1 finding correlate with four observed manual runs and one P2, while individual financial attribution remains unverified. Closure does not waive accounting or budget prerequisites; keep Codex dispatch disabled until they are verified.
- Verify GitHub review-thread state event semantics and API permission boundaries.
- [Issue #24](spikes/issue-24-mcp-events.md) verifies the synthetic MCP Events subscription, delivery and unsubscribe in Work web and Dot through a private tunnel. [Issue #25](development/domain-events.md) adds the durable local outbox and opt-in signed webhooks. The production Work and Dot bridge remains pending.
- Validate OpenAI model names, pricing and any API credits with the actual account and live pricing before establishing production quotas.
- Decide how general comments are linked to tracked findings when the provider cannot produce a structured finding identifier.

## Definition of done

Every phase delivers documented behavior, secured boundaries, tests with real outcomes, updated ADRs, and verified operator visibility. A prototype or accepted ADR does not itself mean a feature is implemented.
