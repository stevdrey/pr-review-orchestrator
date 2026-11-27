# Durable re-review planning from synchronize events

Issue [#72](https://github.com/stevdrey/pr-review-orchestrator/issues/72) connects signed GitHub deliveries to fix correlation, current-head CI and durable review intents. The worker uses the existing sandbox dispatcher for one explicitly selected PR in a single repository.

## Enable the planner

Enable signed webhook ingress and the [read-only sandbox CI worker](ci-and-iteration-policy.md#durable-sandbox-worker), then set `orchestrator.sandbox-ci.synchronize-enabled=true`. The existing sandbox PR, installation pair, repository and workflow IDs still apply. The configured SHA is the starting target for CI before tracking begins. Once a PR is tracked, the planner checks CI for its current head. The optional review evidence reader keeps its own configured SHA.

The read installation needs Pull requests, Actions and Contents read permissions. The adapter issues repository-scoped tokens for each operation and compares the root tree SHAs of the two [Git commit objects](https://docs.github.com/en/rest/git/commits#get-a-commit-object). The worker accepts same-repository PRs. Codex dispatch and author notifications must be disabled in this mode.

Intent creation also requires a trusted `ReviewIntentPolicy` bean that allows the exact PR, provider, stage and SHA under local authorization and budget policy. Without a bean, the worker updates state and records CI evidence but denies new intents. The cap uses `orchestrator.codex.max-requests` (1–6, default 6). It includes existing request reservations and issued review intents that have yet to occupy a reservation slot. A cancelled intent no longer counts. A dead-lettered intent still counts because its effect may have landed.

## Processing and recovery

1. The durable worker claims an authorized delivery. It captures the local PR version and reads the PR through the configured read installation. Both scopes are rechecked when committing. The GitHub response must match the configured PR and repository and corroborate the delivery SHA. A delivery for another SHA completes without changing the tracked head.
2. An accepted synchronize uses the existing head transition and invalidates earlier clean and CI evidence. If remediation is waiting on the quiet period, the worker completes the signed delivery and schedules one internal assessment at the fix correlation deadline in the same transaction. Otherwise the synchronize reads current-head CI before it completes, so CI deliveries processed before it are not lost. The assessment is keyed by PR, head and quiet-period deadline. The synchronize and any CI wake-ups in the same quiet period share it. It keeps the first scheduler's correlation ID and receives its own bounded retry budget. Exact replays keep the original delivery and deadline. An assessment for a superseded SHA or quiet-period deadline completes without planning. A synchronize conflicts only with a newer head observation, not with unrelated state writes. Transaction conflicts are retried from fresh reads. This mode uses a 180-second claim lease. That covers the bounded PR, Git and CI reads, both transactions, and the token issues and revocations.
3. After the quiet period, the worker compares committed trees and obtains fresh current-head CI. Synchronize, check-run and workflow-run deliveries can wake the planner. Only passing CI, a reviewable PR, the existing remediation and disposition gates, available iteration capacity and a permitted intent can create a request. A current fix claim supports re-review while verification remains pending. A denial settles that delivery. A later qualifying signed synchronize or CI delivery can prompt a new assessment.
4. CI, the final eligibility check, the deduplicated `REQUEST_REVIEW` outbox write and inbox completion share a fenced transaction. The intent targets `codex` at `BASELINE` for the exact SHA. The outbox identity is unique across visits to that SHA. Historical reservations for the same target also suppress planning. Local version checks and the PR lock protect concurrent state, findings and iteration decisions.

The sandbox worker leaves outbox commands pending for inspection. Provider send authorization is a separate step. Any future gateway must verify the current head and visit, readiness, consent and a valid budget reservation at dispatch. Live provider completion and the review-to-notice cycle remain in [issue #23](https://github.com/stevdrey/pr-review-orchestrator/issues/23). Disable `synchronize-enabled` to return to pinned CI mode. Preserve the inbox and outbox records.

## Verification

```sh
./gradlew --offline test --tests '*FeedbackLoopSandboxIntegrationTest' --tests '*DurableDispatchConfigurationTest'
./gradlew --offline clean build
git diff --check
```

The sandbox fixture uses signed HTTP ingress, disposable PostgreSQL, local Git commits, a GitHub HTTP fixture and a stub dispatch gateway. It covers exact replay, stale delivery, close pushes, concurrent claims, current-head CI, unresolved general feedback, revoked authorization, human hold, budget denial and the iteration cap. The stub consumes one intent for the final head without contacting GitHub or Codex.

On 2026-11-26, the full build passed 357 tests, including 18 in the sandbox and dispatcher configuration suites, with zero failures or skips. `git diff --check` passed.

The [PR #73](https://github.com/stevdrey/pr-review-orchestrator/pull/73) regression `successfulFinalSynchronizeAttemptStillPlansAfterQuietPeriod` reproduced premature dead-lettering on `b3a506d` with four transient GitHub read failures followed by a successful final synchronize claim. The regression checks that the assessment stays pending until the quiet period ends, can retry its own transient failure, and creates one intent for the exact head without a new CI delivery.
