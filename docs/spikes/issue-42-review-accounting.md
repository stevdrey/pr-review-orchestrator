# Issue 42: Codex review accounting

**Status: investigation in progress; account attribution unresolved.**
Checked on 2026-11-16 (America/Costa_Rica; 2026-11-17 UTC).
Tracked by [issue #42](https://github.com/stevdrey/pr-review-orchestrator/issues/42),
following the [issue #14 trigger experiment](issue-14-codex-attribution.md).
This is a research record and operator protocol, not a production review adapter
or an implemented budget ledger. Keep issue #42 open until the accounting source
or written provider confirmation is obtained.

## Findings and available sources

| Source checked | What it establishes | Accounting limitation |
| --- | --- | --- |
| [GitHub review guide](https://learn.chatgpt.com/docs/third-party/github) | Manual comment and native automatic triggers; optional security review can run alongside code review | Does not identify the charged account/workspace or consumption for an individual review |
| [Pricing](https://learn.chatgpt.com/docs/pricing#what-counts-as-code-review-usage) | GitHub manual and automatic reviews are Code Review usage; API-key access excludes GitHub cloud review | A usage category or published rate is not an individual charge record; do not calculate subscription consumption using API prices |
| [Analytics API](https://learn.chatgpt.com/docs/enterprise/analytics-api) | Workspace-scoped aggregate daily usage; endpoint availability must be confirmed | Not a raw audit log; the guide does not establish a review-to-charge join |
| [Usage Insights](https://learn.chatgpt.com/docs/enterprise/usage-insights#code-review) | Code review measures include PRs reviewed and findings | Activity counts do not identify an allowance debit, credit debit or invoice charge |
| [Desktop usage and Personal Analytics](https://help.openai.com/en/articles/20001478-reviewing-work-and-codex-usage-and-using-personal-analytics-in-chatgpt-desktop#understand-cost-estimates-and-data-freshness) | For eligible Enterprise/Edu workspaces, historical usage charts refresh less frequently than chat/monthly totals; current-day activity may appear later | Supports reporting-delay caution, but does not specify the refresh interval or repeated-PR counting semantics for the owner's Code review chart. Do not apply the guide's roughly one-minute chat/monthly refresh rate to that chart |
| [Compliance API](https://learn.chatgpt.com/docs/enterprise/compliance-api) and [Admin API reference](https://chatgpt.com/public/admin/api-reference) | Supported audit records may help correlate activity; schema and entitlement require verification | Reference was not inspectable through the available web reader beyond its landing page; no authorized workspace export was supplied. An audit event alone is not a billing record |

No authoritative per-review accounting source was established through these
checks. This is an access/evidence limitation, not a claim that the provider has
no such records. No Enterprise/Admin key was requested or provisioned.

The desktop account-limits tool was checked read-only. It exposes current limits
and credit status, without a review/PR/SHA correlation or a historical debit for
either case below. Its account identifiers and balances are intentionally omitted.
The documented usage-dashboard link redirected to the ChatGPT home page in the
available browser; no authenticated dashboard snapshot was obtained.

The owner confirmed on 2026-11-16 that no written Support response is available
and that visible review counts cannot be linked to a specific PR. The owner
proposed a before/after counter experiment. Its results must be recorded separately
from verified billing attribution.

## Separate case assessment

Existing GitHub evidence was re-read on 2026-11-16. The source is
[Dokene PR #393](https://github.com/stevdrey/dokene/pull/393); this is historical
evidence, not authorization to repeat the test.

| Case | Trigger and timing (UTC) | SHA and outcome | Account/workspace, mechanism and measured units |
| --- | --- | --- | --- |
| Explicitly consented GitHub App user token, historical manual test | [User request](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867830855), 2026-11-13 23:46:37 | `5fc61950ed07c1454c690543f1e4e6219bfbc475`; [receipt](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867847540), 23:47:42, reports no major findings and reviewed short SHA `5fc61950ed` | All unverified; no per-review tokens, credits, allowance debit or monetary charge available |
| Native automatic code review, historical test | [Latest summary](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867835659) says new commits; code review completed 2026-11-14 00:18:23 | `cc75b81ae8c93cff415c1ad83bbebed3d490cb79`; summary reports completion without findings | All unverified; simultaneous security review completed 00:20:09, preventing isolation of code-review consumption |

The manual receipt is a useful separate reference because the summary was later
updated for the automatic run. The summary is mutable; neither comment contains
a billing identity or usage debit. GitHub actor identity, PR authorship, completed
review status and clean findings cannot establish the charged account.

## Owner-assisted counter experiment

The owner supplied [Dokene PR #406](https://github.com/stevdrey/dokene/pull/406)
and the baseline below for one manual request. Review settings and isolation
conditions have not been independently verified; no monetary cap was supplied.
One connector-mediated manual request was sent as recorded below.
This protocol simulates a manual request outside the service;
the service has no live Codex command adapter.

1. Have the account operator supply the PR URL and approve exactly one manual
   code review. Record the spending/allowance bound if supplied. A request-count
   cap alone does not cap variable token cost; absent a monetary cap, report that
   limitation and never use this experiment to authorize production dispatch.
2. Record the same account/workspace using a public alias, dashboard section,
   metric name, units, date range, timezone, filters, last-updated time and initial
   value. Keep the alias-to-account mapping private with the operator. Use a
   fixed reporting range covering the whole experiment, including midnight.
3. Check automatic code/security review settings with the operator. Isolate the
   manual case from automatic review and other Codex activity, or record the
   contamination and do not claim isolation. Do not change shared settings
   without authorization. Do not open a test PR or push commits during the
   baseline window if those actions could start automatic reviews.
4. Read authoritative GitHub state: repository ID, open/ready status, author,
   base/head SHA, existing trigger comments and running/completed review evidence.
   Inspect the diff for safe provider submission. Stop on an existing ambiguous
   request or changing head. Use experiment key
   `issue42/<repository-id>/<pr>/<head-sha>/manual-code/1` to identify the single
   attempt in the record; it is a local bookkeeping key, not GitHub idempotency.
5. Verify the credential mechanism before labeling the case. A connector-mediated
   comment does not prove that it used the consented GitHub App user-token flow.
   If that mechanism is unavailable, label the test **connector manual request**,
   not a reproduction of the historical user-token test. Never expose credentials
   or broaden access just to obtain a particular actor.
6. Immediately recheck the head and existing requests, then post exactly one
   `@codex review` comment with the approved mechanism. Record comment URL, actor,
   API/tool, timestamps and any receipt. No automatic retry on a timeout or
   uncertain outcome: reconcile remote comments first. GitHub cannot atomically
   pin an issue-comment trigger to a SHA; if the head races, mark the experiment
   invalid and stop rather than silently reviewing again.
7. Observe completion for at most 10 minutes, with no repeated trigger. Confirm
   the reviewed SHA, including security-review co-triggers. On timeout record
   **outcome unknown**, not rejected or free. Inspect counters after the displayed
   update advances past completion. If freshness is absent, record that fact;
   agree a later observation with the owner, with a 48-hour reporting cutoff for
   this experiment. This cutoff is a local limit, not a provider reporting SLA.
8. Capture the final counter with identical scope and units. Compute the delta
   only for comparable, non-resetting cumulative counters. A count of PRs may
   stay unchanged when an already-counted PR is reviewed again; a percentage,
   share, rolling allowance or reset is not a review count or monetary cost.
9. Report **aggregate activity correlation only** if the counter increases.
   No increase before freshness/cutoff is inconclusive. Delayed previous work,
   concurrent work, security review or scope changes invalidate isolated
   attribution. Even an isolated increment cannot distinguish included allowance,
   paid credits, overage or another account's billing without provider evidence.
10. Record cleanup: temporary credentials revoked, settings restored by the
    authorized operator, and any test PR closed only if created for this purpose.
    Preserve evidence comments; do not delete them as a substitute for revocation.

### Snapshot record (manual review completed; two counter readings unchanged)

Use one row per observation. Do not publish emails, account/workspace IDs,
balances, invoices, tokens, signed URLs or unredacted screenshots.

| Observation | Captured at / provider updated at (with timezone) | Account alias, metric, units, fixed range and filters | Value | Correlation / limitations |
| --- | --- | --- | --- | --- |
| Before | Screenshot filename: 2026-11-16 21:20:34 America/Costa_Rica (2026-11-17 03:20:34 UTC); provider update time not visible | Owner-provided usage view, alias `owner-A`; Code review tab, rolling 7d selected; exact boundaries/timezone not visible | PRs reviewed: **47**; issues found: **12** | Owner supplied the screenshot and PR #406. Screenshot has no PR/account join; an already-reviewed PR may not increase a unique-PR count |
| Trigger | 2026-11-17 03:22:47 UTC (2026-11-16 21:22:47 America/Costa_Rica) | Same owner-provided baseline; provider scope not independently accessible | One manual request | [Comment 6937120015](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937120015), actor `stevdrey`, body exactly `@codex review`; connector credential type unknown |
| Completion | 2026-11-17 03:24:45.607683 UTC (2026-11-16 21:24:45 America/Costa_Rica) | Provider GitHub summary | Completed, no findings reported | [Summary](https://github.com/stevdrey/dokene/pull/406#issuecomment-6919233848) binds full head `1d99ebb0dc4dfdd6e15463379dd8f42dd13cb214`; [separate receipt](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937156523) at 03:24:44 UTC identifies short SHA `1d99ebb0dc`. Security-review state was null |
| First reading after completion | Owner reported unchanged counters after completion; exact observation time and provider update time not supplied | Owner report for the same panel; settings/filters not independently verified | PRs reviewed: **47**; issues found: **12** | Provisional reading; no evidence that reporting had caught up with the completed review |
| Second reading after completion | Owner's second report received by 2026-11-17 03:36:44 UTC (2026-11-16 21:36:44 America/Costa_Rica); exact capture/provider update time not supplied | Owner reports the same panel; settings/filters not independently verified | PRs reviewed: **47**; issues found: **12** | Still no visible increment; reporting settlement, freshness and repeated-PR counting semantics remain unknown |
| Visible difference / cleanup | Both post-completion readings | Rolling 7d activity metrics, not billing units | PRs reviewed: **0** change; issues found: **0** change | No attribution or zero-cost inference; cleanup recorded below |

Screenshot was read from the owner's attachment; no image or private account
identifier was copied into the repository. Treat its timestamp as filename-derived,
not independently verified provider freshness. The rolling window is a limitation
of the supplied view, not a fixed-range baseline. Concurrency and code/security
automatic settings are unknown; do not label the experiment isolated.

Preflight: repository `stevdrey/dokene`, stable repository ID `1344259200`, PR
`406`, author `cuerv0x`, open and non-draft. Base
`04cfe3c8d303bc88c08aa3deaf5928f9f5e99839`, head
`1d99ebb0dc4dfdd6e15463379dd8f42dd13cb214`. Existing manual request
[6919224104](https://github.com/stevdrey/dokene/pull/406#issuecomment-6919224104)
completed on the earlier SHA `671de92cae312300470455a509526aa7f6b4f4f3`;
the inspected summary showed no running review. The public 27-file patch was
inspected without executing PR code. The connected GitHub actor is `stevdrey`;
the connector's credential type is not exposed, so this test is a **connector
manual request**, not verified GitHub App user-token authentication.

Experiment key:
`issue42/1344259200/406/1d99ebb0dc4dfdd6e15463379dd8f42dd13cb214/manual-code/1`.

Execution: immediately re-read open/non-draft/head and existing requests through
`github.get_pr_info` and `github.fetch_pr_comments`. Submitted once using
`github.add_comment_to_issue(repo_full_name="stevdrey/dokene", pr_number=406,
comment="@codex review")`; verified the returned comment independently through
`github.fetch_pr_comments`. An initial call was rejected by connector argument
validation before submission; correcting the argument names produced the one
comment above. No ambiguous remote write was retried.

The provider reported the manual review running from 03:23:06.863153 UTC and
completed at 03:24:45.607683 UTC. Re-read PR state after completion: still open,
head unchanged. The receipt reports no major findings; this does not verify the
code's correctness or establish consumed units. No security review was shown in
the inspected summary, which does not independently verify repository settings.
The owner reported that the counters remained unchanged, then noted that reporting
could be delayed. This is the first post-completion reading, not proof of settled
usage. A further owner-assisted observation around 2026-11-16 21:35
America/Costa_Rica was proposed (2026-11-17 03:35 UTC). That interval is a local
experiment choice, not a provider reporting SLA. The owner subsequently rechecked
and reported the same 47 PRs reviewed and 12 issues found; that report was received
by 03:36:44 UTC. The initial comparison window ends with that second report and
its outcome is **inconclusive**. It does not establish that the counter has settled,
that usage was free, or that either account paid. No investigation PR was opened
during that window. Subsequent repository activity must be treated as outside this
comparison window and could confound a later reading. No second review request
will be sent to force a counter change. A later owner-supplied reading can be
appended with its own capture/update times and concurrent-activity limitations;
no delayed recheck or additional request is automated.

Follow-up documentation check: OpenAI's Desktop usage guide acknowledges delayed
historical/current-day reporting for the eligible workspace surfaces it describes.
It does not establish an update deadline for this owner's Code review counters
or whether repeat reviews of one PR increment `PRs reviewed`. Both remain unknown;
neither a ten-minute wait nor an unchanged total settles billing attribution.

Cleanup for this run: no temporary App/token or test PR was created; no shared
setting was changed, so no credential or setting rollback is required. The
authorized PR and evidence comments remain intact. No code from the PR was run.

Native automatic review remains a **separate** experiment. A manual counter
increment cannot validate it. Before another experiment, require its own approved
PR/event, snapshots, code/security settings, bound and cleanup plan. Do not enable
repository-wide automatic review to fill this evidence gap.

### Second authorized experiment: first review on PR #51

The owner separately authorized exactly one `@codex review` request on the new
[investigation PR #51](https://github.com/stevdrey/pr-review-orchestrator/pull/51)
to investigate whether a first review of a different PR changes the counter.
This is a separate observation window, not a retry of the PR #406 request.

- Preflight: repository ID `1457036208`, PR #51 open and **draft**, author
  `stevdrey`, base `507423c349ebbbaad0900509809a9eef3570c16b`, head
  `5cb41c2b52fbc62fe9cb7411e1b82532202f12af`. GitHub returned no existing
  conversation comments or reviews. The diff contains this investigation's six
  documentation files. The PR was left in draft; no shared setting changed.
- Baseline reference: last owner-reported panel reading was **47 PRs reviewed**
  and **12 issues found**, received by 03:36:44 UTC. No fresh snapshot immediately
  before this second request was supplied. Delayed reporting of PR #406 or other
  activity could therefore affect the comparison. Repository, PR authorship and
  draft state also differ from the first experiment.
- Experiment key:
  `issue42/1457036208/51/5cb41c2b52fbc62fe9cb7411e1b82532202f12af/manual-code/1`.
- `github.add_comment_to_issue` posted exactly one
  [request](https://github.com/stevdrey/pr-review-orchestrator/pull/51#issuecomment-6937488661)
  at **2026-11-17 03:40:53 UTC** (2026-11-16 21:40:53 America/Costa_Rica),
  actor `stevdrey`, body exactly `@codex review`. Credential type is not exposed.
  The subsequent read was interrupted; a fresh read verified the existing comment
  without resubmitting it.
- [Provider summary](https://github.com/stevdrey/pr-review-orchestrator/pull/51#issuecomment-6937494471)
  reported a manual code review running from **03:41:06.138558 UTC** on the full
  expected head. It reported completion at **03:42:15.627909 UTC**
  (2026-11-16 21:42:15 America/Costa_Rica), with no findings. The
  [separate receipt](https://github.com/stevdrey/pr-review-orchestrator/pull/51#issuecomment-6937519196)
  was created at 03:42:14 UTC and identifies short SHA `5cb41c2b52`.
  Security-review state was null. Completion does not establish billed identity,
  units or charge mechanism.
- First post-completion counter reading: the owner again reported **47 PRs
  reviewed / 12 issues found**, received by **2026-11-17 03:44:25 UTC**
  (2026-11-16 21:44:25 America/Costa_Rica). Exact capture time, selected filters
  and provider update time were not independently available. Visible difference
  from the last baseline is zero for both metrics. The owner suggested a larger
  refresh window; this remains plausible but unverified. The reading is
  provisional, not evidence of zero consumption or rejection of this first review.
- Any later observation must record capture/update times, the same reporting
  scope, and intervening activity. No further review request will be sent to force
  movement. The existing 48-hour local reporting cutoff is not a provider SLA;
  even expiry without a visible increment leaves attribution inconclusive.
- No temporary credentials, test PR or setting change requires rollback. Hold
  evidence pushes while this review and comparison are in progress so the
  reviewed head stays stable. This does not enable any service request adapter.

## Production decision and escalation

Under [ADR 0002](../adr/0002-github-app-webhook-trust.md) and
[ADR 0004](../adr/0004-deterministic-policy-and-budget-gates.md), keep both Codex
request paths disabled in the orchestrator while usage attribution is unknown.
Do not record missing usage as zero, release a reservation based on a clean
receipt, or treat a dashboard increment as permission to dispatch. Future
adapters must reserve an enforceable upper bound before billable work, retain
ambiguous settlement for reconciliation, and stop further dispatch when usage
cannot be reconciled. No budget enforcement is implemented by this document.

Fallback: hand control to the owner for an eligible manual workflow outside the
service, or select a separately approved provider with verified billing identity,
observable units and enforceable budget controls. Native automatic review is
subject to the same accounting prerequisite. No automatic merge is authorized.

Escalation owner: repository/account owner. Use the
[prepared Support request](issue-42-provider-confirmation-request.md) through the
[official Support channel](https://help.openai.com/en/articles/6614161-how-can-i-contact-support).
**Prepared, not submitted; no provider response received.** Public evidence does
not establish that Support is unable to supply the missing records. Keep the
ticket ID and raw response private; publish only a redacted conclusion approved
by the owner. Reopen accounting verification when a supported source or written
confirmation becomes available, separately for each trigger and account context.

## Verification and operation log

- `git rev-parse HEAD origin/main` and the network-authorized
  `git ls-remote origin refs/heads/main` confirmed the same base:
  `507423c349ebbbaad0900509809a9eef3570c16b`.
- Read issue #42 and its comments through the GitHub connector; no issue comments
  were present. Re-read PR #393 evidence using `github.fetch_pr_comments`.
- Read the official sources linked above and checked desktop account limits;
  retained only source capabilities and limitations, not private account data.
- Initial read-only `git ls-remote origin refs/heads/main` failed sandbox DNS;
  the network-authorized retry succeeded.
- Exactly two separately authorized review requests were posted, one per
  experiment, as recorded above. No provider Support
  messages, credential creation or shared settings changes were performed.
  No tokens or private usage exports were saved.
- Earlier issue #14 cleanup is recorded in its report; it was not repeated or
  independently reverified here.
- `./gradlew build` succeeded; all 8 tasks were up-to-date, including `test`.
  Existing build/test outputs were reused; tests were not rerun. No runtime code,
  database schema, credentials or production configuration changed.
- `git diff --check` passed. A local Python check validated local Markdown link
  targets and checked common credential patterns across all six changed
  documents; passed. These checks do not validate external billing attribution.

Acceptance remains partial: individual accounting and written confirmation are
missing; the two historical cases, two new connector manual experiments and safe
fallback are documented. All owner-reported post-completion counter readings
showed no increase, but reporting freshness is unknown. Manual trigger/completion
evidence is confirmed for both new tests; per-review accounting remains unknown.

| Issue #42 criterion | Current evidence / remaining requirement |
| --- | --- |
| Authoritative accounting source or written confirmation | Not achieved: no per-review account/workspace, correlation or mechanism established; provider request prepared, not submitted |
| Separate manual and automatic assessments | Historical cases and two new connector manual tests documented; units and billing identity remain unknown for each. Reported counters unchanged after both experiments; comparisons inconclusive |
| Safe production decision and fallback | Documented: both service request paths stay disabled, missing usage cannot bypass budget gates, owner handoff or separately approved metered provider |
| Commands, dates, sanitized evidence and cleanup | Recorded above; two separately authorized requests with SHA-bound completion, two owner-reported counter readings for the first experiment and one for the second, no new credentials or settings to undo. Provider reporting freshness remains unavailable |
