# CI reconciliation and review limits

Issue #21 adds the internal CI gate, lifetime iteration accounting and deterministic stops for automatic review. They use the authorized PR scope and the same PostgreSQL PR lock as head changes and review evidence.

## Reconcile required CI

1. Read the local `ReviewLifecycle` version before fetching the current PR and required CI from authorized sources. Resolve the PR in the installation and repository scope. Use signed `check_run` and `workflow_run` inbox events to trigger that read. Their SHA is routing metadata.
2. Supply a `CiEvidence` snapshot for the authoritative PR head to `observeCi`, or call `reconcileCi` for an explicit refresh. The approved caller supplies the configured required dependency set and marks the read complete only when it has established the current attempts for that set. Identify check runs by app ID and check name, and workflows by workflow ID. Verify each observation's origin and PR association. Use a new observation UUID for a new read.
3. Map only a completed, successful required run to `PASSED`. Active runs are `PENDING`. Failed terminal runs are `FAILED`, and unavailable or unrecognized states are `UNKNOWN`. Skipped, neutral and cancelled runs cannot pass. A rerun supersedes the previous attempt even while pending.

Every required dependency must be present exactly once and pass at the exact head SHA. Empty policy, incomplete reads, missing dependencies and ambiguous current attempts cannot pass. A check on a synthetic merge commit requires separate policy support. An event for an earlier commit is ignored. A stale-version conflict requires a new local version and a fresh remote read.

CI is journaled and reset to `UNKNOWN` at a head update or same-head confirmation. A new non-passing result drops `READY_FOR_HUMAN` back to `DEEP_REVIEW`, including the resume target of a paused state. Both clean reviews remain valid at that SHA. Readiness also requires the existing eligibility and finding checks. A re-review requires passing CI before reserving a request and before the external effect. This applies after remediation starts, on a new visit to a previously observed SHA or after an earlier request for the same stage.

## Stop conditions

- **Iteration limit.** Each automatic request reserves one permanent slot per installation, repository and PR. The default ceiling is six and the Codex configuration accepts a lower ceiling. Slots span SHAs, epochs, providers, stages, crashes, unsent requests and uncertain outcomes. A retry or reconciled duplicate reuses the prior row. A new request at the limit commits `PAUSED` with `ITERATION_LIMIT` before returning `LIMIT_REACHED`. A human resume leaves the count intact.
- **No progress.** `AssessReviewOutcome.assess` records a fingerprint for actionable findings from an authenticated, current completed review. It sorts the finding kinds, paths and exact body hashes from that review. Provider IDs for individual comments and shifted line numbers do not affect the fingerprint. Two consecutive distinct requests for the same provider and stage with identical nonempty fingerprints produce `NEEDS_HUMAN / NO_PROGRESS` and persist a pause. Dismissed and verified-fixed findings, unrelated reviews and stale snapshots are excluded. The hard ceiling still applies when repeated findings are reworded or provider results are ambiguous.

Read the active reason from `ReviewLifecycle.find(pr).intervention()` and its history from `pr_review_transitions`. An explicit, authorized `RESUME` clears the current hold after human triage. The stored attempts and progress evidence are retained. The author notification channel is tracked in [issue #22](https://github.com/stevdrey/pr-review-orchestrator/issues/22).

## Rollout and verification

V14 preserves request IDs, keys, states and receipts. It assigns iteration slots to retained requests and initializes CI to unknown, withdrawing unsupported legacy readiness while retaining clean verdicts and history. Keep the schema and audit evidence during rollback. Disable review dispatch and readiness writes if rolling back to an older binary, which cannot enforce the new gates.

The internal reconciliation entry points require an approved caller to read current GitHub state, evaluate the configured dependencies and revalidate them at dispatch. The existing default-disabled provider and dispatcher settings still apply. Local fixtures use synthetic event, CI and provider observations with disposable PostgreSQL. Live CI fetching, notification delivery and the end-to-end GitHub/Codex workflow remain separate integrations.

Local verification used Java 27 and disposable PostgreSQL 17 via Testcontainers. `./gradlew --offline clean build` passed all 262 tests with zero failures or skips. The suite covers stale SHA and rerun events, ambiguous dependencies, competing requests for the final slot, no-progress replay, and the V13-to-V14 upgrade. `git diff --check` passed.
