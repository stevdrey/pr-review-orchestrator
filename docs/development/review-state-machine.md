# Persistent PR review state machine

Implements [issue #15](https://github.com/stevdrey/pr-review-orchestrator/issues/15) under ADRs [0001](../adr/0001-independent-modular-monolith.md), [0003](../adr/0003-durable-event-inbox-outbox.md) and [0005](../adr/0005-finding-state-and-head-sha.md). This is an internal Java use case with PostgreSQL persistence. No event planner, HTTP/MCP action, provider invocation, notification or merge is enabled by this increment.

## Identity and persistence

`PullRequestId` combines stable installation/repository IDs and PR number. `PullRequestReview` is an immutable, provider-independent aggregate with head SHA, version, state, optional resume state and baseline/deep clean SHAs. Initialization creates `NEW` at version zero; it cannot overwrite an existing PR. Full lowercase 40- or 64-character commit IDs are required.

Flyway V5 adds `pr_review_states` and `pr_review_transitions`. Existing provider/stage/iteration `review_cycles`, findings, commands and their evidence are preserved. There is intentionally no backfill from old cycles: they cannot establish the authoritative current head. New callers initialize an aggregate only after fetching that head. Provider/stage cycles remain separate records and are not automatically updated by this use case.

`ReviewLifecycle` calls the `ReviewStateStore` port with paired deployment/live repository authorization on every operation, including reads and duplicate attempts. `JdbcReviewStateStore` checks authorization inside a bounded, independent transaction; JDBC membership share locks prevent revocation from completing during that transaction. Mutations lock one PR row, check the transition key, expected version and expected SHA, apply the pure domain transition and atomically commit state plus a metadata-only journal entry. The transaction/network deadline is three seconds and requires PostgreSQL 17+; the store checks `transaction_timeout` support at startup. Callers must not hold outer transaction locks on these rows while calling the independent transaction.

## Transitions and guards

Every command carries a caller-stable UUID, expected version, expected head SHA, action and non-null `Gates`. Only `UPDATE_HEAD` carries a new SHA. Accepted actions increment the version exactly once, including clean evidence recorded without changing the state.

| Action | From | Result and additional guard |
| --- | --- | --- |
| `MARK_READY` | `NEW` | `WAITING_FOR_REVIEW`; open, not draft, no human hold |
| `START_REVIEW` | `WAITING_FOR_REVIEW` | `REVIEWING`; eligible and review policy permits |
| `BLOCKING_FINDINGS` | `REVIEWING`, `DEEP_REVIEW`, `READY_FOR_HUMAN` | `ACTION_REQUIRED`; blocking findings observed; clear both clean SHAs |
| `AWAIT_FIX` | `ACTION_REQUIRED` | `WAITING_FOR_FIX` |
| `BASELINE_CLEAN` | `REVIEWING` | `DEEP_REVIEW`; eligible, no blocking findings; record baseline clean SHA |
| `DEEP_CLEAN` | `DEEP_REVIEW` | Keep state, record deep clean SHA once; eligible, no blocking findings |
| `MARK_READY_FOR_HUMAN` | `DEEP_REVIEW` | `READY_FOR_HUMAN`; both reviews clean for current SHA, eligible, no blockers, CI explicitly `PASSED` |
| `PAUSE` | Any except `PAUSED` | Save resume state and clean evidence; enter `PAUSED` |
| `RESUME` | `PAUSED` | Restore saved state; restoring `WAITING_FOR_REVIEW`, `REVIEWING`, `DEEP_REVIEW` or `READY_FOR_HUMAN` requires eligibility, and `READY_FOR_HUMAN` also rechecks CI and blockers; `NEW`, `ACTION_REQUIRED` and `WAITING_FOR_FIX` permit no review work and resume ungated |
| `UPDATE_HEAD` | Any | Require different SHA; clear both clean SHAs; `NEW` stays `NEW` (only `MARK_READY` is the gated entry); other states enter `WAITING_FOR_REVIEW`, or stay paused with that resume state (a `NEW` resume target stays `NEW`) |

`DEEP_REVIEW` identifies the required review stage; entering it does not dispatch a provider call. Clean deep evidence can be stored while CI is pending; readiness is a separate guarded action. This increment conservatively requires both clean stages. Optional-stage policy, separate budget/failure states and automatic no-progress stops remain future integrations; operators/callers can explicitly pause the current aggregate.

After every head change, including force-push and a return to a previously reviewed SHA, both clean fields are null. No resume, thread-resolution flag or old provider result can restore them. `UPDATE_HEAD` changes local observed state, not GitHub. Starting another review still requires eligibility and policy permission. A new SHA is required to leave `WAITING_FOR_FIX` automatically; same-head finding dispositions and re-review scheduling remain separate work.

## Rejection and concurrency

Authorization failure takes precedence over state visibility. For authorized mutations, rejection order is missing aggregate, previously used transition UUID, stale version, stale expected SHA, illegal action/state pair, then failed guards. Stable `ReviewConflict.Code` values are `NOT_FOUND`, `DUPLICATE_TRANSITION`, `TRANSITION_ID_REUSED`, `STALE_VERSION`, `STALE_HEAD`, `ILLEGAL_TRANSITION`, `GUARD_REJECTED`; creation uses `ALREADY_EXISTS`. Rejected commands change neither aggregate nor journal. A previously used UUID is `DUPLICATE_TRANSITION` only when the journaled expected version, expected SHA, action and resulting SHA match the retried command; a UUID reused for any other command is `TRANSITION_ID_REUSED` and must not be treated as committed. Other persistence failures, including deferred COMMIT failures, surface as `ReviewStateUnavailable` with a stable message, no cause and a `retryable()` flag (false for an invalid persisted snapshot).

The transition UUID is scoped to installation/repository/PR and must remain unchanged on retry. Replays remain `DUPLICATE_TRANSITION` after later transitions or a restart. A fresh UUID with an old version is `STALE_VERSION`; a fresh version with an old SHA is `STALE_HEAD`. A semantically repeated action with a fresh UUID/version is rejected if the graph does not permit it (for example repeated `BASELINE_CLEAN` or `DEEP_CLEAN`). Concurrent distinct commands against one version have exactly one successful local commit. After an ambiguous database response, read state and reuse the original UUID; never invent a new key to force advancement.

## Trust boundary and integration requirements

`Gates` and clean actions are trusted internal inputs for the command's expected SHA. They are not proofs gathered by this component. A future approved caller must fetch authoritative current PR head/open/draft/hold status, correlate normalized provider evidence with the actual request/stage/SHA, derive blocking findings and required CI from authoritative sources, and obtain deterministic action/approval/budget decisions. Unknown CI cannot satisfy readiness. A provider comment, GitHub thread checkbox, untrusted model response or signed webhook SHA alone must never be mapped directly to a clean transition or an allowed policy decision.

The expected version protects local ordering; it cannot establish remote chronological order. Fetch and reconcile the remote head before constructing a head update, especially after delayed webhooks or force pushes. Do not refresh an old command's version to make stale evidence pass. Future command gateways must recheck authorization, head and approval/spending gates at the actual side-effect boundary, even if local state says ready. This component neither calculates spending nor reserves a review invocation; no autonomous actions are introduced.

The journal stores keys, actions, versions, SHAs, resulting states and database timestamps. It contains no source/review text, raw gates, tokens or arbitrary evidence URLs. Database constraints enforce snapshot shape and SHA consistency; the domain enforces the transition graph. SQL access still requires deployment isolation: no row-level security, immutable audit or production retention automation is added.

## Verification and rollout

```sh
./gradlew test --tests '*PullRequestReviewTest' --tests '*ReviewStateIntegrationTest'
./gradlew clean build
git diff --check
```

The focused tests cover the entire action/state graph, full fix/review cycle, every state's head invalidation, force-push back to an old SHA, stale/duplicate commands, all pause/resume states, CI/eligibility/policy guards, immutable concurrent updates, real PostgreSQL races, restart persistence, scope isolation, authorization denial/unavailability, deferred commit rollback/retry, V4→V5 migration and database evidence constraints. PostgreSQL tests use disposable containers and synthetic metadata, with no GitHub or provider credentials. Missing Docker fails the tests.

V5 is additive and creates empty tables; it does not enable background work or infer existing PR readiness. Before rollout, back up the database and validate migrations using the [database runbook](database.md). Stop callers before rollback. There is no down migration; preserve journals and use a reviewed forward correction or verified backup restore instead of dropping evidence. Live GitHub/provider integration, production deployment and end-to-end review/repair/notification behavior remain unverified.

Local implementation verification on 2026-11-16 (after PR #48 review fixes) (America/Costa_Rica), Java 27 and disposable PostgreSQL 17.11:

- Focused command above: passed, 42 tests, no failures or skips.
- `./gradlew clean build`: passed, 137 tests, no failures, errors or skips. Existing Flyway-history assertions now include V5.
- `git diff --check` and relative documentation links: passed.

GitHub Actions must be checked independently against the PR's exact head SHA. These results do not establish live provider behavior or production rollout readiness.
