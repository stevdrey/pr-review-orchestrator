# Fix correlation for re-review

Issue #20 adds the internal Spring bean `CorrelateFixes`. It correlates authorized PR head changes and retained findings and can record one baseline re-review candidate for the latest commit. A candidate is an eligibility record. Dispatch still requires fresh GitHub state, CI and iteration policy, provider authorization, deduplication and budget approval.

## Reconcile and assess

1. For pushes to a PR branch, use the existing signed `pull_request/synchronize` inbox route. This includes fork-based PRs, with the installation and repository scope of the PR. Read the local lifecycle version before fetching the authoritative PR head. Pass the inbox event and the fresh, scoped head to `observeSynchronize`. An event whose head differs from the authoritative head is ignored. The journal deduplicates each event. A new confirmation of the same head advances the version and invalidates earlier clean evidence and head-specific dispositions because the PR may have moved away and returned. After a stale-version rejection, repeat both reads.
2. For `pull_request_review_thread/resolved` or `unresolved`, fetch the complete thread and review evidence. Use `GitHubReviewNormalizer.normalizeForRound` with the existing round SHA and the expected head of the current authorized read. The adapter validates the latter against `headRefOid` and preserves both SHAs. Pass the event and snapshot to `observeThread`, reusing the snapshot ID and content for an exact retry. A resolved thread remains an observation and its pending finding retains its blocking status.
3. Call `assess` with the current local version captured before the authoritative reads, the current remote PR head, and human-hold state. The last `BLOCKING_FINDINGS` or `AWAIT_FIX` transition supplies the comparison base. A distinct head update must follow it. The 30-second quiet period starts again on each new accepted synchronize observation, including a same-head confirmation. Exact delivery retries leave the deadline intact. `WAITING_FOR_PUSHES` gives the earliest retry time using PostgreSQL time. Re-read current state before retrying.
4. Supply an authoritative comparison of the committed trees at the returned base and current head. Compare the contents of the two committed trees, including across force-pushes. A changed SHA with an identical tree is insufficient. `COMPARISON_REQUIRED` means the comparison is missing or targets a different pair of SHAs. A return to the original head or an equal tree yields `NO_NEW_CODE`.
5. Evaluate retained findings under the same PR lock as lifecycle changes, snapshots and dispositions. A pending inline finding can justify re-review after a change even if the conversation remains open. Each general finding must have a current audited dismissal or a fix claim whose evidence SHA equals the current head. Acknowledgments, edits after a decision, mismatched or stale claims, and legacy general findings without a decision block eligibility. A valid fix claim remains blocking for a clean verdict and keeps verification pending. If all findings are dismissed or otherwise cleared, there is no remediation work to re-review.

Run the assessment after reconciliation and again at the reported retry time. General dispositions and thread observations can prompt a fresh assessment without restarting the head-change timer. Closed, draft, paused or human-held PRs are ineligible. Stale local versions and head mismatches are rejected. A prior baseline request reservation for the current head blocks a second request for that head.

## Persistence and rollout

V10 adds the `CONFIRM_HEAD` journal action and `fix_review_candidates`. A candidate records the baseline SHA, target SHA, head-change journal version and creation time. The per-PR transaction and unique installation/repository/PR/SHA key serialize concurrent assessments. `ELIGIBLE` creates the row and `ALREADY_ELIGIBLE` returns its ID when a fresh assessment still qualifies. Reuse the candidate's ID as a work reference. Historical rows record previous eligibility. Before consuming one, reassess against fresh evidence and use the existing provider/stage/SHA request key and independently enforced dispatch gates.

V11 adds the observed PR head to review snapshots. V1–V10 checksums and existing evidence are preserved. The quiet period is recovered from the lifecycle journal. Keep the additive schema and audit history for application rollback. Older code assumes the observed head matches the round, so pause review outcome evaluation before rolling back after ingesting historical-round observations with different heads. An approved reconciliation worker must drive the internal use case, fetch fresh GitHub state and recover due assessments after restart. CI reconciliation and iteration policy belong to [issue #21](https://github.com/stevdrey/pr-review-orchestrator/issues/21). Live GitHub fetching, scheduler wiring and provider dispatch require their approved integrations.

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
