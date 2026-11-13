# Architecture Decision Records

ADRs capture persistent architectural intent. **Accepted** means the decision is accepted for implementation, **not** that any runtime feature is shipped.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-independent-modular-monolith.md) | Independent modular monolith and ports/adapters | Accepted |
| [0002](0002-github-app-webhook-trust.md) | GitHub App and verified webhooks | Accepted |
| [0003](0003-durable-event-inbox-outbox.md) | Durable inbox/outbox and idempotency | Accepted |
| [0004](0004-deterministic-policy-and-budget-gates.md) | Hard policy, budget and iteration gates | Accepted |
| [0005](0005-finding-state-and-head-sha.md) | Findings and head-SHA-specific verdicts | Accepted |
| [0006](0006-provider-neutral-agents-and-events.md) | Optional adapters for review providers and MCP events | Accepted |

## Writing a new ADR

Filename: `NNNN-short-description.md`; sections: **Status**, **Context**, **Decision**, **Consequences**, **Alternatives**, **Verification**. Changing an accepted decision requires a successor ADR linking the superseded one. ADRs are implementation commitments; revise the roadmap/issues when the underlying decision changes.
