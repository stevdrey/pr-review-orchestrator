# CI reconciliation and review limits

Issue #21 adds the internal CI gate, lifetime iteration accounting and deterministic stops for automatic review. They use the authorized PR scope and the same PostgreSQL PR lock as head changes and review evidence.

## Reconcile required CI

1. Read the local `ReviewLifecycle` version before fetching the current PR and required CI from authorized sources. Resolve the PR in the installation and repository scope. Use signed `check_run` and `workflow_run` inbox events to trigger that read. Their SHA is routing metadata.
2. Supply a `CiEvidence` snapshot for the authoritative PR head to `observeCi`, or call `reconcileCi` for an explicit refresh. The approved caller supplies the configured required dependency set and marks the read complete only when it has established the current attempts for that set. Identify check runs by app ID and check name, and workflows by workflow ID. Verify each observation's origin and PR association. Use a new observation UUID for a new read.
3. Map only a completed, successful required run to `PASSED`. Active runs are `PENDING`. Failed terminal runs are `FAILED`, and unavailable or unrecognized states are `UNKNOWN`. Skipped, neutral and cancelled runs cannot pass. A rerun supersedes the previous attempt even while pending.

Every required dependency must be present exactly once and pass at the exact head SHA. Empty policy, incomplete reads, missing dependencies and ambiguous current attempts cannot pass. A check on a synthetic merge commit requires separate policy support. An event for an earlier commit is ignored. A stale-version conflict requires a new local version and a fresh remote read.

CI is journaled and reset to `UNKNOWN` at a head update or same-head confirmation. A new non-passing result drops `READY_FOR_HUMAN` back to `DEEP_REVIEW`, including the resume target of a paused state. Both clean reviews remain valid at that SHA. Readiness also requires the existing eligibility and finding checks. A re-review requires passing CI before reserving a request and before the external effect. This applies after remediation starts, on a new visit to a previously observed SHA or after an earlier request for the same stage.

## Stop conditions

- **Iteration limit.** Each automatic request reserves one permanent slot per installation, repository and PR. The default ceiling is six and the Codex configuration accepts a lower ceiling. Slots span SHAs, epochs, providers, stages, crashes, unsent requests and uncertain outcomes. A retry or reconciled duplicate reuses the prior row. A new request at the limit commits `PAUSED` with `ITERATION_LIMIT` before returning `LIMIT_REACHED` when the lifecycle tracks the PR and it is not already paused. An existing human or automatic hold keeps its reason and version. A PR without lifecycle state receives `LIMIT_REACHED` and no aggregate is created. A human resume leaves the count intact.
- **No progress.** `AssessReviewOutcome.assess` fingerprints actionable findings from an authenticated, current completed review. It compares finding kinds, exact body hashes and the path and line of inline findings. Two consecutive distinct requests for the same provider and stage with identical nonempty fingerprints produce `NEEDS_HUMAN / NO_PROGRESS` and persist a pause unless the previous review has verified progress. The previous request is the latest earlier one that may have reached the provider; `RESERVED` and `NOT_SENT` requests are skipped, while uncertain (`ATTEMPTED`) or unfingerprinted rounds still break the sequence. Each request keeps the fingerprint from its latest assessed snapshot and records whether it caused the pause. A request pauses at most once: replays report `NO_PROGRESS` only for the request that caused a pause still in force, and newer evidence never re-pauses after a human resume. The comparison uses the effective inline disposition across cycles for the same stable finding and exact source revision. A verified fix recorded after the previous review's snapshot establishes progress even if the next review repeats its wording and location. The comparison shares the pending-findings precedence rules, including the effect of a newer open occurrence. Dismissed and verified-fixed findings are excluded from the current fingerprint. The iteration ceiling still bounds rounds with changed or missing inline locations, reworded findings or ambiguous provider results.

Read the active reason from `ReviewLifecycle.find(pr).intervention()` and its history from `pr_review_transitions`. An explicit, authorized `RESUME` clears the current hold after human triage. The stored attempts and progress evidence are retained. The author notification channel is tracked in [issue #22](https://github.com/stevdrey/pr-review-orchestrator/issues/22).

A late completion during an existing pause updates the request's fingerprint and returns its actionable outcome. The journal identifies the request that caused each no-progress pause. A replay reports that pause only while it is the active hold. An earlier hold with unlinked journal evidence remains in place and its replay reports the actionable outcome.

An authenticated completed assessment with no comparable findings stores the latest snapshot with a null fingerprint. This breaks the comparison with the next round and retains the request's pause attribution. Recording a clean outcome also clears the request's comparable fingerprint.

An authenticated `FINDINGS` conclusion can make a previous `UNSTRUCTURED` assessment comparable on the same snapshot. A change to the snapshot, review source ID or fingerprint triggers comparison when the resulting fingerprint is comparable and the request is eligible to pause.

## Rollout and verification

V14 preserves request IDs, keys, states and receipts. It assigns iteration slots to retained requests and initializes CI to unknown, withdrawing unsupported legacy readiness while retaining clean verdicts and history. V15 invalidates cached V14 fingerprints because V14 did not record uncomparable assessments. It keeps the assessment snapshots and pause history. V15 also clears persisted CI that the journal cannot tie to the current head visit and resets CI on journaled head updates and confirmations from older writers.

Keep the schema and audit evidence during rollback. Stop review dispatch, evidence writes and all lifecycle writers while an older binary is connected to the retained schema. On roll-forward, reconcile the current PR head and required CI before reactivating automation or readiness writes.

The internal reconciliation entry points require an approved caller to read current GitHub state, evaluate the configured dependencies and revalidate them at dispatch. The existing default-disabled provider and dispatcher settings still apply. Local fixtures use synthetic event, CI and provider observations with disposable PostgreSQL. Live CI fetching, notification delivery and the end-to-end GitHub/Codex workflow remain separate integrations.

Local verification used Java 27 and disposable PostgreSQL 17 via Testcontainers. `./gradlew --offline clean build` passed all 262 tests with zero failures or skips. The suite covers stale SHA and rerun events, ambiguous dependencies, competing requests for the final slot, no-progress replay, and the V13-to-V14 upgrade. `git diff --check` passed.

### PR #65 no-progress regression

With the regression applied to `e47bd6b`, `./gradlew --offline test --tests '*.ReviewStateIntegrationTest.inlineProgressAccountsForLocationAndVerifiedFixes'` reproduced three incorrect pauses. The four cases check a new inline location, a verified fix even when the next finding reuses the original location, and an unresolved repeat that still pauses.

The focused run (`./gradlew --offline test --tests '*.ReviewStateIntegrationTest' --tests '*.ReviewOutcomeClassifierTest'`) passed 49 tests. The final `./gradlew --offline clean build` passed all 266 tests, including the four regression cases, with zero failures or skips.

With the follow-up regression applied to `6e46e43`, `./gradlew --offline test --tests '*.ReviewStateIntegrationTest.inlineProgressUsesTheEffectiveVerifiedFixAcrossCycles'` reproduced the false pause when a later cycle held the verified fix. The regression also covers a verified fix for a different source revision and a newer open occurrence that supersedes the verified fix.

The follow-up focused run (`./gradlew --offline test --tests '*.ReviewStateIntegrationTest' --tests '*.FixCorrelationIntegrationTest' --tests '*.GeneralFeedbackIntegrationTest'`) passed 80 tests. The final `./gradlew --offline clean build` passed all 269 tests, with zero failures or skips.

### PR #65 pause attribution regression

The four new PostgreSQL test cases reproduced incorrect attribution on `828ce3a`. They cover a late repeated review and its replay during no-progress, iteration-limit and human holds, plus an earlier pause owner replayed during a later hold. The focused run (`./gradlew --offline test --tests '*.ReviewStateIntegrationTest' --tests '*.ReviewRequestIntegrationTest'`) passed all 56 tests after the fix.

`./gradlew --offline clean build` passed all 278 tests, with zero failures or skips.

### PR #65 latest evidence and rollback regressions

With the regressions applied to `a5ccd9e`, the two incomplete-location cases reproduced stale comparison state or lost pause attribution. The V13 writer fixtures reproduced a passing CI status after both a head update and a same-head confirmation. The V14-to-V15 fixture covers repair of already stale CI, preservation of a valid observation, and verification that invalidating old hashes retains snapshot and pause evidence.

The focused run (`./gradlew --offline test --tests '*.ReviewStateIntegrationTest' --tests '*.ReviewOutcomeClassifierTest' --tests '*.ReviewRequestIntegrationTest' --tests '*.DatabaseIntegrationTest'`) passed 93 tests with zero failures or skips.

`./gradlew --offline clean build` passed all 283 tests with zero failures or skips.
