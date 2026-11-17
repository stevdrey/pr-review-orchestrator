# Issue 42: delayed counter update and open-PR correlation

**Result: aggregate activity increased, but individual attribution remains
unresolved.** This extends the [accounting investigation](issue-42-review-accounting.md).
The owner supplied a second screenshot named `Screenshot From 2026-11-16
22-39-24.png`; filename time is interpreted as America/Costa_Rica
(2026-11-17 04:39:24 UTC), not independently verified provider freshness.
No screenshot or private account identifiers were copied into the repository.

## Counter comparison

| Metric, Code review tab with 7d selected | Baseline, 21:20:34 local | Later capture, 22:39:24 local | Visible change |
| --- | --- | --- | --- |
| PRs reviewed | 47 | 49 | **+2** |
| Issues found | 12 | 12 | **0** |

Intermediate owner readings stayed at 47/12 after the two controlled requests.
The later capture is 78 minutes 50 seconds after the baseline and approximately
57 minutes after completion of the PR #51 test. These are observation intervals,
not measured refresh latency or a provider reporting SLA. The account/workspace
and provider update timestamp are not shown. The reporting period is rolling;
exact date boundaries and timezone are not established.

## Open-PR audit scope

On 2026-11-16 at approximately 22:41 local (2026-11-17 04:41 UTC), GitHub
connector searches `user:stevdrey is:open` and `involves:stevdrey is:open`, each
with `topn=100`, returned 11 and 8 PRs respectively. Their union contained 11
PRs across five repositories; none of the returned lists reached the limit.
Read each PR's merged conversation/review/inline-comment timeline using
`github.fetch_pr_comments`, retaining actual user-authored trigger comments
separately from provider summaries and receipts. Re-read current metadata for
the three PRs with triggers and the submitted reviews for Dokene #410.

| Open PRs returned | Actual manual `@codex review` comments in available history | Within baseline-to-later-capture window |
| --- | --- | --- |
| [Dokene #406](https://github.com/stevdrey/dokene/pull/406) | 2 | 1; the other was 2026-11-16 08:27:50 local, before baseline |
| [PR Review Orchestrator #51](https://github.com/stevdrey/pr-review-orchestrator/pull/51) | 1 | 1 |
| [Dokene #410](https://github.com/stevdrey/dokene/pull/410) | 2 | 2 |
| FARO [#90](https://github.com/stevdrey/faro/pull/90), [#92](https://github.com/stevdrey/faro/pull/92), [#93](https://github.com/stevdrey/faro/pull/93), [#94](https://github.com/stevdrey/faro/pull/94) | 0 | 0; timelines empty |
| Monada Forge [#41](https://github.com/stevdrey/monada-forge/pull/41), [#42](https://github.com/stevdrey/monada-forge/pull/42), [#43](https://github.com/stevdrey/monada-forge/pull/43) | 0 | 0; timelines empty |
| [Monada Resonance Store #196](https://github.com/stevdrey/monada-resonance-store/pull/196) | 0 | 0; timeline empty |
| **Total** | **5** | **4** |

This audit covers PRs returned by these two searches at inspection, not all
historical or closed PRs, deleted comments, other identities, or every repository
that could contribute to the account's seven-day counter. It does not attempt to
reconstruct all 49 recorded reviews. Empty timelines establish no visible request
or provider review there, not zero billable activity elsewhere.

## Four requests during the observation window

All times below are 2026-11-16 America/Costa_Rica; add six hours for UTC on
2026-11-17. All four trigger comments were attributed by GitHub to `stevdrey`.
Only the first two were sent by this investigation; the two on #410 were found
read-only during this audit. No new trigger was sent to perform the comparison.

| PR | Request time and source | Reviewed commit | Provider result and time |
| --- | --- | --- | --- |
| Dokene #406 | [21:22:47](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937120015) | `1d99ebb0dc4dfdd6e15463379dd8f42dd13cb214` | [Receipt](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937156523) at 21:24:44; summary completed 21:24:45, no findings reported |
| Orchestrator #51 | [21:40:53](https://github.com/stevdrey/pr-review-orchestrator/pull/51#issuecomment-6937488661) | `5cb41c2b52fbc62fe9cb7411e1b82532202f12af` | [Receipt](https://github.com/stevdrey/pr-review-orchestrator/pull/51#issuecomment-6937519196) at 21:42:14; summary completed 21:42:15, no findings reported |
| Dokene #410, first run | [21:55:13](https://github.com/stevdrey/dokene/pull/410#issuecomment-6937754479) | Receipt identifies short SHA `fbafe0b547` | [Submitted review](https://github.com/stevdrey/dokene/pull/410#pullrequestreview-5932554900) at 21:57:33, with [one visible P2 finding](https://github.com/stevdrey/dokene/pull/410#discussion_r4595103520) |
| Dokene #410, second run | [22:02:43](https://github.com/stevdrey/dokene/pull/410#issuecomment-6937881442) | `f0ff3fa2b0d0e8b34be66853aa9281fd3ba33b0e` | [Receipt](https://github.com/stevdrey/dokene/pull/410#issuecomment-6937905481) at 22:04:05; summary completed 22:04:06, no findings reported |

The [latest #410 summary](https://github.com/stevdrey/dokene/pull/410#issuecomment-6937759331)
describes only the second run. It must not erase the first submitted review or
its finding from this history. At inspection #410's current head was
`1d8ed22d431ffd216b01c200fe4369541851d558`, which differs from both reviewed
commits; no fresh clean verdict is inferred for that head. #406 retained its
reviewed head and #51 retained `5cb41c2`; #51 was still a draft.

## Interpretation and remaining evidence

- The supplied account view now shows two additional units of review activity.
  Its initial unchanged readings were insufficient to establish settled counts.
- The increase is **consistent with** delayed recording of the two controlled
  requests, but does not identify them: two additional requests and their provider
  outcomes on #410 occurred before the later screenshot. The window was not isolated.
- Four visible requests and a +2 counter difference do not establish whether
  `PRs reviewed` counts runs, unique PRs, or another grouping. Delayed batches,
  reporting scope and rolling-window boundaries remain possible explanations.
  Two concrete hypotheses fit the review-count delta: a delayed batch containing
  the two clean controlled runs (#406 and #51), or a unique-PR count adding the
  two newly reviewed PRs (#51 and #410) while not incrementing repeats. Neither
  hypothesis is established by the screenshot.
- The visible P2 finding on #410 alongside an unchanged Issues found total is
  another mismatch to investigate. The screenshot does not establish whether that
  finding is included, delayed, filtered or aggregated differently. Do not infer
  billing or fix verification from the latest no-findings summary.
- Account allowance, credits, invoice charge, units consumed per run and native
  automatic-review attribution remain unknown. Preserve the fail-closed production
  decision and request provider correlation/semantics through the
  [prepared Support request](issue-42-provider-confirmation-request.md).

The later screenshot closes this comparison at its filename-derived capture
time. Evidence publication or any subsequent reviews are outside that window.
Keep issue #42 open; no additional billable experiment is authorized by this audit.
