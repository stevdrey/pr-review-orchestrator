# Durable inbox/outbox dispatch

Implements [issue #13](https://github.com/stevdrey/pr-review-orchestrator/issues/13) and ADR [0003](../adr/0003-durable-event-inbox-outbox.md). This is the durable execution infrastructure. Review triggering, finding interpretation, notification adapters and spending policies remain separate roadmap increments.

## Activation and adapter boundary

Dispatch is **disabled by default**. Webhook acceptance continues to commit metadata without invoking a provider. `orchestrator.dispatch.enabled=true` enables an inbox tick and an outbox tick every second on separate scheduler threads, each draining at most 16 items, with PostgreSQL 17+ required. General dispatch requires both an approved `EventPlanner` and `CommandGateway` bean. The opt-in [CI sandbox worker](ci-and-iteration-policy.md#durable-sandbox-worker) uses the same inbox tick and completes local CI observations under the inbox lease. It leaves the command queue idle. Production review dispatch still requires approved adapters.

`EventPlanner` performs bounded, read-only planning from authorized metadata. It returns at most 32 command intents, or a stable `Failure` code. It must resolve missing PR/SHA metadata through an authoritative provider read. Existing CI/workflow ingress rows have a SHA but no PR number; general-comment rows have a PR number but no SHA. The infrastructure does not guess those associations or convert comment/thread events into finding resolutions. Unsupported/legacy routes must be rejected explicitly by the planner, rather than silently acknowledged as completed work.

Each intent supplies PR number, full expected SHA, stage, provider and a registered command type. Identifiers are bounded safe tokens. The queue adds the event's installation/repository scope and derives the deterministic key:

```text
installation/repository/pr/headSHA/stage/provider/type
```

The initial cycle uses iteration 1. Review-loop iteration, policy/budget reservations and repeat-stage authorization are not implemented here. A retry always uses the original command/key. Another event does not create another command with the same logical identity. Legacy/noncanonical outbox keys are dead-lettered as `INVALID_EVENT`; they are not automatically converted into a second executable identity.

`CommandGateway` must explicitly support the command before remote reads. Reconciliation must obtain the authoritative current PR head and durable remote evidence for that command key:

| Evidence | Dispatcher behavior |
| --- | --- |
| Effect already applied, including after the current head changes | `SUCCEEDED` for the original command/SHA; no repeated write |
| Effect is unknown, ambiguous or not yet settled, for any current SHA | Retry reconciliation with `REMOTE_UNCERTAIN`; at the attempt ceiling, `DEAD` with that code; no write |
| Effect is authoritatively absent and current SHA differs from expected SHA | `CANCELLED`; no write |
| Current SHA matches and effect is authoritatively absent | Call the approved gateway under the held authorization locks |

Evaluate these rows in order: confirmed remote execution remains durable evidence even after a new PR head supersedes the command's expected SHA. `SUCCEEDED` records the effect for that original key/SHA; it does not establish a clean review or any other verdict for the new head.

A changed head prevents another write but does not prove that an earlier attempted effect is absent. Preserve `UNKNOWN` across head changes and restarts until authoritative reconciliation settles it or the existing retry ceiling produces a `REMOTE_UNCERTAIN` dead letter. Only authoritative `ABSENT` evidence permits stale-head cancellation.

Gateway calls together must finish within the 10-second database transaction deadline, using strict provider request deadlines. An executing gateway must enforce remote idempotency, fresh SHA at the actual effect boundary, and all required action, approval and budget gates. The initial dispatcher SHA read cannot atomically fence a later GitHub head change. A gateway that cannot implement those guarantees must refuse execution. There is no generic GitHub write, OAuth, merge, AI prompt or billable provider implementation in this increment.

## Transactions, recovery and limits

- Claims use PostgreSQL `clock_timestamp()` and `FOR UPDATE SKIP LOCKED`, with a fresh random fencing token and 30-second lease. Each claim, including crash recovery, consumes an attempt. Multiple processes can share the database without a JVM clock ordering assumption.
- Inbox completion locks the current, unexpired token, rechecks live paired authorization and atomically inserts/reuses cycles and outbox commands, marks the inbox processed and appends a journal transition. A commit failure rolls all those changes back. Planning happens outside that transaction and must have no side effects.
- Dispatch locks the current token and authorization rows through bounded reconciliation, the effect and terminal commit. Claims skip a locked command even if its lease expires during that call. A former owner cannot confirm, fail or execute a reclaimed command. Database/network/transaction deadlines bound lock retention; remote idempotency remains necessary if the process or DB connection dies while an external operation continues.
- Defaults are five attempts per stored item, with exponential retry delay of 15, 30, 60, 120 seconds (about 3.75 minutes of retry window), capped at five minutes for rows configured with more attempts. `Limits` validates lease/backoff bounds. Attempts never reset on restart. This ceiling is a transport limit, not authorization for five billable actions or review iterations.
- Recovery also moves final-attempt crashes to `DEAD`, clearing leases and setting terminal timestamps. Each claim transaction sweeps at most 32 exhausted rows. Unknown remote outcomes remain reconciliation-only until resolved or dead-lettered. Any retryable failure after `execute` has started (including a lost response, deadline or commit failure) is recorded as `REMOTE_UNCERTAIN`, so a final-attempt dead letter signals that the effect may have landed and must be reconciled before any redrive. Unsupported commands, invalid routes (including inbox rows without a repository, as `INVALID_EVENT`) and confirmed authorization denial are terminal; other `SecurityException`s are `INTERNAL_ERROR`. Authority query failures use sanitized `AUTHORIZATION_UNAVAILABLE` with bounded retries; no planning or effects proceed while authorization is uncertain. Unexpected adapter exceptions use `INTERNAL_ERROR` with bounded retries.

These are at-least-once execution semantics. Exactly-once external effects require a provider-supported idempotency/conditional-write mechanism or authoritative durable reconciliation. An eventually consistent search returning no marker after a timeout does **not** establish absence. Preserve ambiguity as `UNKNOWN`, including after restart. The fixture gateway proves the protocol against a synthetic strongly idempotent remote system; it does not establish GitHub API capabilities or live review entitlement.

## Operator visibility and recovery

V3 adds fencing tokens, `queue_transitions` and `queue_dead_letters`. V4 extends the journal error-code constraint with `AUTHORIZATION_UNAVAILABLE`, preserving all existing V3 evidence. It preserves V1/V2 deliveries, digests, retry ceilings and command identities. Existing `PROCESSING` rows without a token can be reclaimed after lease expiry. The journal stores local work/correlation IDs, queue, attempt, outcome, stable error code and time. Dispatcher logs contain only those local IDs/codes; runtime exceptions are normalized to new stable-code failures inside the transaction callback, before Spring handles rollback. Raw causes and suppressed exceptions are discarded, including on known failures. This prevents provider exception bodies from reaching Spring DEBUG rollback logs or its ERROR log when rollback itself fails. No raw event, token or source text is logged by this component.

Use a trusted database operator session; no new public diagnostics endpoint is exposed:

```sql
SELECT * FROM queue_dead_letters ORDER BY completed_at DESC LIMIT 100;
SELECT * FROM queue_transitions
WHERE correlation_id = '00000000-0000-0000-0000-000000000000'
ORDER BY occurred_at, id;
SELECT id, command_key, expected_head_sha, status, attempts, max_attempts,
       available_at, locked_until, correlation_id, last_error_code
FROM outbox_commands ORDER BY created_at DESC LIMIT 100;
```

Dead letters are terminal. There is no automatic reset/redrive or deletion job: investigate authorization, remote settlement and exact SHA before a reviewed operator recovery. Reusing/resetting a command without remote reconciliation can duplicate effects. Production retention, alerting and database access control remain deployment work. The journal is transactional evidence, not an immutable security ledger.

To disable ticks, set `orchestrator.dispatch.enabled=false` and restart. Preserve database state; pending or expired in-flight work resumes when compatible approved adapters are enabled. Disabling this worker does not disable webhook ingress. V3/V4 are additive; keep migrations immutable and use the [backup/forward migration procedure](database.md), rather than dropping queue tables or changing checksums.

## Verification

```sh
./gradlew test --tests '*DurableDispatch*' --tests '*JdbcWorkQueueLoggingTest' --tests '*RepositoryAuthorizationTest' --tests '*DatabaseIntegrationTest'
docker compose --env-file .env.example config --quiet
./gradlew clean build
git diff --check
```

Tests use disposable PostgreSQL and two installation/repository scopes. They cover concurrent/replayed logical events, atomic deferred-commit rollback, stale-owner fencing, restart/lease recovery, crashes on final attempts, remote acceptance followed by lost response/process death, unknown outcomes, stale/out-of-order SHAs, authorization revocation, poison messages, capped backoff, sanitized correlation logs, concurrent dispatch and lease expiry during a locked effect. Configuration tests cover default-disabled startup and fail-closed activation without approved adapters. Regression tests inject transient membership-authority errors at both inbox and outbox authorization checks, assert persisted retries and recovery after queue replacement, and prove the attempt ceiling still stops persistent outages. `JdbcWorkQueueLoggingTest` captures actual Spring transaction logs at DEBUG and at INFO with an injected JDBC rollback failure, including raw and known failures carrying unsafe causes/suppressed exceptions. A V3-to-V4 migration fixture preserves prior journal evidence and keeps the stable-code allowlist enforced. Existing HTTP webhook tests cover signed delivery replay and durable acceptance.

Live GitHub writes, actual process killing, network partitions against a remote deployment, production throughput/retention and complete review orchestration are not verified by these local fixtures. Record exact local and commit-specific CI results in the implementation PR.

Local verification on 2026-11-14 (Java 27, disposable PostgreSQL 17.11): the initial focused integration run passed 27 tests; the final `./gradlew clean build` passed 80 tests with 0 failures, 0 errors and 0 skipped. Compose validation and `git diff --check` passed. The full build includes 15 new durable-dispatch integration tests and three new configuration/identity tests. These results are local; GitHub Actions evidence is recorded separately on the PR's published head.

After the two PR #47 review fixes, the focused command covering dispatch, authorization, logging and database tests passed 41 tests. The subsequent `./gradlew clean build` passed 91 tests with 0 failures, 0 errors and 0 skipped; Compose validation and `git diff --check` also passed. The added V3-to-V4 upgrade fixture was included in that full build. Failure injection exercises actual PostgreSQL queue persistence and Spring transaction/rollback logging, without claiming a live infrastructure outage or remote provider run.

The applied-effect reconciliation regression was reproduced on `1ba669dc3308951dc6ccc8749d9eb9759147314b`: both lost-response and deferred-commit-failure cases incorrectly ended as `CANCELLED` after the PR head advanced. Giving confirmed `APPLIED` evidence precedence fixed both cases. `./gradlew test --tests '*DurableDispatchIntegrationTest'` passed all 21 dispatch integration cases, and `./gradlew clean build` passed 92 tests with 0 failures, 0 errors and 0 skipped. Compose validation and diff checks passed. The tests verify the original command key/SHA and successful journal outcome, no repeated write and no review cycle for the newer SHA.

On 2026-11-15, the uncertain-effect/head-advance regressions reproduced premature cancellation at `40cf702736969b4a4f4d2f0dbdd2f2a01176e0d1`: the unchanged-head control passed while both stale-head cases failed. Checking `UNKNOWN` before stale-head cancellation preserves reconciliation through later `APPLIED` settlement or the existing `DEAD/REMOTE_UNCERTAIN` attempt ceiling. All 24 dispatch integration cases and the complete 95-test build passed, with 0 failures, errors or skipped tests. Compose validation and diff checks passed. Regression assertions cover persisted retry/error metadata, the original key/SHA, no cancellation journal entry, no repeated write, no newer-head review cycle and no automatic redrive after exhaustion. No schema change or live provider test was required.
