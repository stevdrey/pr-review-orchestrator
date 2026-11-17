# Issue 42: prepared provider confirmation request

**Draft only. Not submitted.** The account owner should send this through
[OpenAI Support](https://help.openai.com/en/articles/6614161-how-can-i-contact-support)
after checking the content. Add private account/workspace details only within the
provider's secure support channel, never in this repository.

Subject: Per-review usage attribution for manual and automatic Codex GitHub reviews

We are validating an integration before enabling automated review requests. We
can observe aggregate GitHub Code Review activity but cannot correlate an
individual review with the account/workspace and usage mechanism charged.

Please assess these cases separately:

- Manual review: consented GitHub App user access token, GitHub actor `stevdrey`;
  [request on Dokene PR #393](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867830855)
  at 2026-11-13 23:46:37 UTC, head
  `5fc61950ed07c1454c690543f1e4e6219bfbc475`;
  [completion receipt](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867847540)
  at 23:47:42 UTC.
- Native automatic review: new commits on the same PR, head
  `cc75b81ae8c93cff415c1ad83bbebed3d490cb79`;
  [summary](https://github.com/stevdrey/dokene/pull/393#issuecomment-6867835659)
  records code review completion at 2026-11-14 00:18:23 UTC and a separate
  security review completion at 00:20:09 UTC.
- Owner-authorized connector manual request: [Dokene PR #406 request](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937120015)
  at 2026-11-17 03:22:47 UTC, head
  `1d99ebb0dc4dfdd6e15463379dd8f42dd13cb214`;
  [receipt](https://github.com/stevdrey/dokene/pull/406#issuecomment-6937156523)
  at 03:24:44 UTC and summary completion at 03:24:45 UTC. The GitHub actor is
  `stevdrey`, but the connector does not expose its credential type. Do not treat
  this as verification of the GitHub App user-token flow. Before this request,
  the owner's Code review view showed 47 PRs reviewed and 12 issues found with
  7d selected. The owner reported the same counters twice after completion,
  including a second check reported by 2026-11-17 03:36:44 UTC. Exact capture
  times and provider update time are unavailable, so these readings may precede
  reporting settlement. The counter experiment is inconclusive.

For each case, could you confirm:

1. Which account/workspace was charged, and how it was selected? Does the rule
   depend on the requester, PR author, connected identity, repository settings,
   personal settings or another context? Please distinguish supported behavior
   from confirmation about these specific runs.
2. Did it consume included allowance, purchased credits, committed workspace
   credits, overage, USD usage or another mechanism? Please distinguish resource
   consumption from a new invoice charge and separate code/security usage.
3. Which supported dashboard/export/API exposes the correlation? We need the
   review/run identifier, PR and reviewed SHA, account/workspace context, measured
   units, timestamps/timezone, reporting delay, retention and entitlement limits.
   If these records are unavailable, please confirm that visibility limitation
   and whether historical attribution can still be supplied in writing.
4. Is there a supported preflight/reservation or enforceable upper usage bound
   for one GitHub review? What happens if consumption is delayed or unknown?

We will not infer billing identity from comment authorship or use daily count
changes as proof of a per-review charge. We can provide account details privately
if needed, without sharing access tokens or private keys.

After receiving a response, retain the original privately and append a sanitized
source/date, case-specific conclusion, units, correlation method and visibility
limits to the [investigation record](issue-42-review-accounting.md). A generic
pricing explanation alone does not verify these individual reviews.
