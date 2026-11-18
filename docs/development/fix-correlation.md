# Fix correlation for re-review

Issue #20 adds the internal Spring bean `CorrelateFixes`. It correlates authorized PR head changes and retained findings and can record one baseline re-review candidate for the latest commit. A candidate is an eligibility record. Dispatch still requires fresh GitHub state, CI and iteration policy, provider authorization, deduplication and budget approval.

## Reconcile and assess

1. For pushes to a PR branch, use the existing signed `pull_request/synchronize` inbox route. This includes fork-based PRs, with the installation and repository scope of the PR. Read the local lifecycle version before fetching the authoritative PR head. Pass the inbox event and the fresh, scoped head to `observeSynchronize`. An event whose head differs from the authoritative head is ignored. The journal deduplicates each event. A new confirmation of the same head advances the version and invalidates earlier clean evidence and head-specific dispositions because the PR may have moved away and returned. After a stale-version rejection, repeat both reads.
2. For `pull_request_review_thread/resolved` or `unresolved`, fetch the complete thread and review evidence. Use `GitHubReviewNormalizer.normalizeForRound` with the existing round SHA and the expected head of the current authorized read. The adapter validates the latter against `headRefOid` and preserves both SHAs. Pass the event and snapshot to `observeThread`, reusing the snapshot ID and content for an exact retry. A resolved thread remains an observation and its pending finding retains its blocking status. A read at a PR head that the lifecycle has since moved away from is rejected as a conflict; repeat the read. A head that is not yet journaled is accepted because it can lead its pending synchronize. The event carries no thread ID, so the snapshot is not checked for the triggering thread.
3. Call `assess` with the current local version captured before the authoritative reads, the current remote PR head, and human-hold state. The last `BLOCKING_FINDINGS` or `AWAIT_FIX` transition supplies the comparison base. A distinct head update must follow it. The 30-second quiet period starts again on each new accepted synchronize observation, including a same-head confirmation. Exact delivery retries leave the deadline intact. `WAITING_FOR_PUSHES` gives the earliest retry time using PostgreSQL time. Re-read current state before retrying.
4. Supply an authoritative comparison of the committed trees at the returned base and current head. Compare the contents of the two committed trees, including across force-pushes. A changed SHA with an identical tree is insufficient. `COMPARISON_REQUIRED` means the comparison is missing or targets a different pair of SHAs. A return to the original head or an equal tree yields `NO_NEW_CODE`.
5. Evaluate retained findings under the same PR lock as lifecycle changes, snapshots and dispositions. A pending inline finding can justify re-review after a change even if the conversation remains open. Each general finding must have a current audited dismissal or a fix claim whose evidence SHA equals the current head. Acknowledgments, edits after a decision, mismatched or stale claims, and legacy general findings without a decision block eligibility. A valid fix claim remains blocking for a clean verdict and keeps verification pending. If all findings are dismissed or otherwise cleared, there is no remediation work to re-review.

Run the assessment after reconciliation and again at the reported retry time. General dispositions and thread observations can prompt a fresh assessment without restarting the head-change timer. Closed, draft, paused or human-held PRs are ineligible. Stale local versions and head mismatches are rejected. The first visit to a SHA starts at epoch 0, preserving its existing request key. Returning after a journaled departure or confirming the same head starts a new epoch at that transition's journal version. A prior baseline reservation for the current SHA and epoch blocks a second candidate in that epoch. Pass the epoch from the eligible decision as `ReviewRequest.headEpoch`. Stale epochs are rejected before new reservations or sends and cannot establish clean evidence. The lifetime cap includes all reservations across epochs. Durable outbox command keys remain per SHA until provider dispatch is wired.

When lifecycle state exists, reserving or sending requires its tracked SHA to match the request. Both send phases check the tracked SHA and the effective epoch under the PR lock. A remote return to the requested SHA leaves the previous permit blocked until the return is journaled and a current request is reserved.

## Persistence and rollout

V10 adds the `CONFIRM_HEAD` journal action and `fix_review_candidates`. A candidate records the baseline SHA, target SHA, head-change journal version and creation time. The per-PR transaction and unique installation/repository/PR/SHA/head-change-version key serialize concurrent assessments. The head-change version determines the base. `ELIGIBLE` creates the row and `ALREADY_ELIGIBLE` returns its ID when a fresh assessment of the same head change still qualifies. Both return the current request epoch. A return to an earlier candidate SHA after new blockers creates a new candidate for the new base. Reuse the candidate's ID as a work reference. Historical rows record previous eligibility. Before consuming one, reassess against fresh evidence and use the provider/stage/SHA/epoch request key and independently enforced dispatch gates.

V11 adds the observed PR head to review snapshots. V12 adds `findings.round_observed_at`, the last same-head capture time of an occurrence's current source revision, backfilled from retained evidence. Ingestion writes it, so the pending-findings guard no longer reconstructs it per query. An occurrence that was only seen in another round's read yields to a same-head observation of the identical revision in another round. General findings, dispositions and closed-inline checks already match on the exact source revision. V12 also adds `review_requests.head_epoch`; epoch 0 keeps the existing request keys. V1–V10 checksums and existing evidence are preserved. The quiet period is recovered from the lifecycle journal. Keep the additive schema and audit history for application rollback. Older code assumes the observed head matches the round, so pause review outcome evaluation before rolling back after ingesting historical-round observations with different heads. An approved reconciliation worker must drive the internal use case, fetch fresh GitHub state and recover due assessments after restart. CI reconciliation and iteration policy belong to [issue #21](https://github.com/stevdrey/pr-review-orchestrator/issues/21). Live GitHub fetching, scheduler wiring and provider dispatch require their approved integrations.

V13 associates existing reservations made during an already-journaled active return with that visit's epoch. It preserves the original key, epoch, UUID, receipt and lifetime slot. A request for the reconciled epoch finds the existing reservation. When a reservation and the return have identical timestamps, the reservation suppresses duplicates and awaits reconciliation for sending or clean evidence. Reservations that predate the return remain associated with their earlier visit. Use the upgraded application for these effective-epoch checks.

## Verification

```sh
./gradlew test --offline --tests '*FixCorrelationIntegrationTest' --tests '*GeneralFeedbackIntegrationTest' --tests '*ReviewStateIntegrationTest' --tests '*DatabaseIntegrationTest' --tests '*GitHubWebhookIntegrationTest'
./gradlew clean build --offline
git diff --check
```

The integration tests exercise close pushes, stale events, one eligible candidate across concurrent workers and restarts, thread resolution without code changes, equal trees, force-push back to the original SHA, explicit general dispositions, existing baseline requests and revoked authorization. The fixtures use disposable PostgreSQL and synthetic events and commit comparisons. Live GitHub and provider behavior remain unverified.

Verified locally on 2026-11-18 with Java 27 and disposable PostgreSQL. All 82 focused tests and all 238 tests in the full build passed. Both runs had zero failures, errors or skipped tests. `git diff --check` and relative Markdown link validation passed.

### PR #64 review fixes

The return to a settled SHA and the historical-round thread read both reproduced their failures on the earlier PR head. The corrected tests cover delivery replay without extending the deadline, readiness invalidation after the return, and an old thread resolved after a push using the real GitHub normalizer and PostgreSQL ingestion. A snapshot captured at a different PR head cannot establish a clean verdict for its round.

```sh
./gradlew test --offline --tests '*FixCorrelationIntegrationTest' --tests '*GitHubReviewNormalizerTest' --tests '*ReviewStateIntegrationTest' --tests '*ReviewOutcomeClassifierTest' --tests '*GeneralFeedbackIntegrationTest' --tests '*ReviewEvidenceIntegrationTest' --tests '*PullRequestReviewTest' --tests '*DatabaseIntegrationTest'
./gradlew clean build --offline
git diff --check
```

On 2026-11-18 the focused run passed 136 tests and the full build passed 242 tests with Java 27 and disposable PostgreSQL. Both runs had zero failures, errors or skipped tests.

The follow-up regression reproduced `NO_PENDING_FINDINGS` and an incorrect clean transition on `0e1800c` using `./gradlew test --offline --tests '*FixCorrelationIntegrationTest.historicalResolutionWithDismissedOccurrenceCannotHideCurrentOpenFinding'`. The corrected guard retains the current round's open finding even when a later snapshot omits it. The historical round retains its own disposition and resolution metadata.

The follow-up passed 67 focused tests and all 243 tests in `./gradlew clean build --offline` with Java 27 and disposable PostgreSQL. Both runs had zero failures, errors or skipped tests.

```sh
./gradlew test --offline --tests '*FixCorrelationIntegrationTest' --tests '*ReviewStateIntegrationTest' --tests '*ReviewEvidenceIntegrationTest' --tests '*GeneralFeedbackIntegrationTest'
```

The inverse regression reproduced a reopened pending finding on `14bc0d0` with `./gradlew test --offline --tests '*FixCorrelationIntegrationTest.historicalResolutionPreservesValidClosureUntilTheSourceRevisionChanges'`. The guard now recovers the matching-head observation for the same source revision from retained evidence. The regression covers historical refreshes of both occurrences, a finding first seen in a historical read, and a source edit that reopens the finding and revokes clean readiness.

The same focused command passed 69 tests, and `./gradlew clean build --offline` passed all 245 tests with Java 27 and disposable PostgreSQL. Both runs had zero failures, errors or skipped tests.

### High-effort review fixes

These changes cover a historical read that reopened a later round's closed finding, CONFIRM_HEAD stalling a PR on request deduplication, a stale candidate returned after a return to an earlier SHA, outdated historical reads, and replayed head changes that reported `false`. The timeline now loads from one journal scan. The regressions are in `FixCorrelationIntegrationTest`.

### Request epochs after a return

The regression reproduced `ALREADY_REQUESTED` on `aa8cc8c` after moving from a reserved head to a new remediation base and back. After the fix it verifies one candidate and one new reservation for the return, inert retries, stale-send rejection and lifetime cap enforcement. A separate test verifies that a completion from the previous epoch stays ineligible as clean evidence after a fresh observation.

```sh
./gradlew test --offline --tests '*FixCorrelationIntegrationTest.returnToAnEarlierCandidateHeadOpensANewRequestEpochForTheCurrentBase'
./gradlew test --offline --tests '*FixCorrelationIntegrationTest' --tests '*ReviewRequestIntegrationTest' --tests '*ReviewStateIntegrationTest' --tests '*GeneralFeedbackIntegrationTest'
./gradlew clean build --offline
```

On 2026-11-18 the focused suite passed 77 tests and the full build passed 250 tests with Java 27 and disposable PostgreSQL. Both runs had zero failures, errors or skips.

### Stale sends and upgrade compatibility

The regressions reproduced an external-effect attempt after a journaled departure and a missed V8 reservation on `95a7ab3`. They now cover departures before permit consumption and between the send phases, an actual V8-to-V13 upgrade with the original request key and receipt, and a timestamp tie that still suppresses duplicate requests.

```sh
./gradlew test --offline --tests '*FixCorrelationIntegrationTest.sendRejectsEarlierHeadBeforeTheRemoteReturnIsJournaled' --tests '*FixCorrelationIntegrationTest.v8ReservationForAnAlreadyJournaledReturnSurvivesUpgrade'
./gradlew test --offline --tests '*FixCorrelationIntegrationTest' --tests '*ReviewRequestIntegrationTest' --tests '*ReviewStateIntegrationTest' --tests '*DatabaseIntegrationTest' --tests '*ReviewEvidenceIntegrationTest' --tests '*GeneralFeedbackIntegrationTest'
./gradlew clean build --offline
```

On 2026-11-18 the focused suite passed 103 tests and the full build passed 254 tests with Java 27 and disposable PostgreSQL. Both runs had zero failures, errors or skips.
