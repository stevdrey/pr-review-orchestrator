# Codex review requests (issue #18)

**Implemented internal adapter; production dispatch remains disabled.** The
[issue #14 spike](../spikes/issue-14-codex-attribution.md) observed a consented
GitHub App user-token comment triggering Codex. It did not verify individual
accounting. [Issue #42](../spikes/issue-42-review-accounting.md) accepted that
research limitation without waiving ADR 0002/0004. No real review was requested
while implementing this adapter.

## Internal contract and activation

`ReviewProvider.request(ReviewRequest)` accepts stable installation/repository/PR,
full head SHA, provider/stage and verified event metadata. `CodexReviewAdapter`
supports only `codex` / `BASELINE`, and PR opened, ready, synchronize and reopened
triggers. Bot senders and **all comment/review events** return `IGNORED`, including
the consenting user's own trigger comment and provider replies. This deliberately
does not implement a comment-based manual-command workflow. Event IDs, senders and
cycle IDs cannot change the logical request identity.

This is an internal API, with no new public endpoint, scheduler, event planner or
webhook-to-request wiring. A future caller must authenticate the event, fetch
complete authoritative state, preserve the sender ID/type, and enforce trigger
eligibility. Never construct trusted policy inputs from comment instructions.

The default `ReviewProvider` throws `DISABLED` before accessing a credential or
network. Enabling `orchestrator.codex.enabled=true` requires all of:

- A positive `orchestrator.codex.actor-id` (stable GitHub user ID), paired repository
  authorization and PostgreSQL 17+.
- An approved `CodexUserCredentials` implementation that owns consented GitHub App
  user OAuth, encrypted secret storage, refresh/revocation and exact App/installation/
  repository binding. Grant only selected-repository Pull requests write access
  (plus mandatory metadata), within the user’s effective repository access. None
  ships. `/user` verifies the actor and rejects a Bot,
  but does **not** attest whether a token is a PAT or a particular App user token;
  the credential source must establish its provenance. Do not supply PATs or
  installation tokens. Never expose credentials in prompts, logs or persisted rows.
- An approved `CodexApproval` implementation verifying current consent, supported
  trigger/account/repository configuration, entitlement, **individual accounting**,
  approval/holds, CI/findings/no-progress policy and reserved hard budgets at PR,
  installation and global levels. None ships. Reserve by `request.key()` and make
  repeated checks idempotent, including after reservation or send failures. Missing
  usage is unknown, never zero. Budget reconciliation/release is the implementation's
  responsibility; an uncertain send cannot release a potentially consumed budget.

The property alone cannot enable the adapter without these beans. Credential and
approval operations must be bounded so each send transaction completes within eight
seconds (HTTP itself uses at most four one-second exchanges). The lifetime attempt
cap defaults to six per installation/repository/PR across all SHA/provider/stage
reservations in this store; `orchestrator.codex.max-requests` may lower it to 1–6.
This is a conservative emergency ceiling, not the multi-provider monetary ledger.
Do not deploy a permissive test approval implementation.

## Durable protocol and results

V8 adds `review_requests`: metadata-only UUID, canonical scoped key, actor ID,
request SHA/provider/stage, state, timestamps and numeric comment receipt. The key
is installation/repository/PR/SHA/stage/provider, independent of actor, event and
review cycle. Both canonical-key and composite unique constraints enforce it.

1. Serialize reservations per PR, check live authorization/policy and lifetime cap,
   then commit `RESERVED`. Failed preflight after this point conservatively consumes
   a slot; repeated delivery cannot create a second reservation or budget charge.
2. Recheck guards and durably consume a one-shot send permit (`ATTEMPTED`). A crash
   before/after either commit leaves a tombstone. No automatic retry/redrive exists.
3. Hold live installation/repository authorization locks and the request row during
   the bounded effect transaction. Recheck policy, acquire a fresh scoped user
   credential, verify `/user`, and fetch PR state/number/base repository ID/head SHA.
   Only an open, explicitly non-draft PR at the requested SHA is eligible.
4. Recheck authorization, consent/policy and expiry immediately before posting the
   fixed JSON body `{"body":"@codex review"}` to the PR **conversation comment**
   endpoint. No PR-authored text, extra prompt, custom URL or review-thread action
   enters the command. The HTTP client never follows redirects or retries; it caps
   successful response bodies at 256 KiB and bounds headers and bodies together.
5. Validate receipt author/body/ID, fetch PR state again, then commit
   `COMMENT_CREATED`. This is a GitHub comment receipt, not a completed Codex review.

A duplicate completed request returns `DUPLICATE`. Any existing `RESERVED` or
`ATTEMPTED` row returns `OUTCOME_UNKNOWN`, without executing the send again.
Lost responses, malformed receipts, post-write SHA changes or local commit failure
retain the consumed permit even if a comment already exists remotely. The store
itself prevents reuse of the send permit after rollback or restart.

GitHub's comment endpoint has no atomic expected-SHA precondition or remote
idempotency key. A concurrent push can occur between the last read and POST, or
immediately after the verification read. A trigger can therefore cause a review of
a later SHA. **Never infer reviewed SHA or clean evidence from this receipt.**
Ingest independent provider evidence with its actual reviewed SHA. The adapter is
not registered as `CommandGateway`: its API cannot satisfy that gateway's stronger
remote idempotency and SHA contract. No exactly-once external guarantee is claimed.

## Errors and recovery

Exceptions carry stable codes only, without raw GitHub responses, SQL or nested
causes. The internal caller must surface the code and this runbook to an operator;
there is no user-facing action endpoint yet.

| Code / result | Operator action |
| --- | --- |
| `DISABLED` | Keep disabled until approved credential and accounting/budget integrations exist. |
| `UNSUPPORTED_TRIGGER` | Use the supported baseline adapter or a separately approved provider; do not fall back to a bot comment or mutate auto-review settings. |
| `CAPABILITY_UNVERIFIED` | Verify account connection, supported user trigger, consent, entitlement and individual accounting. An account-connection reply is failure evidence, not a retry instruction. |
| `ACTOR_DENIED` | Reauthorize the intended App user; verify stable actor, installation/repository binding and expiry. |
| `PR_NOT_READY` / `STALE_HEAD` | Fetch authoritative PR state; never reuse old SHA evidence. Existing reservation remains a tombstone. |
| `LIMIT_REACHED` / `POLICY_DENIED` | Human intervention and approved budget/policy work; do not reset the counter or change identity to evade the limit. |
| `REMOTE_UNAVAILABLE` | Inspect connectivity/permissions safely. The existing key remains reserved even if preflight failed. |
| `OUTCOME_UNKNOWN` / `STORE_UNAVAILABLE` | Stop. Inspect the row and remote comments/review activity under authorized read access. An absent search result cannot prove no request was received. Never delete/reset the tombstone to retry. |

A provider connection/setup reply, missing reaction or missing review does not prove
zero usage. Do not retry the same key. A successful GitHub POST also does not prove
Codex accepted the trigger; outcome ingestion and authenticated human reconciliation
remain separate work. No alternate-provider invocation, notification, automatic
merge or review-state transition occurs here.

Operator query (metadata only):

```sql
SELECT request_key, actor_id, state, comment_id, created_at, completed_at
FROM review_requests
WHERE installation_id = :installation_id AND repository_id = :repository_id
  AND pr_number = :pr_number
ORDER BY created_at;
```

## Verification and rollout

Focused verification:

```sh
./gradlew test --tests '*ReviewRequestIntegrationTest' --tests '*GitHubCodexCommentsTest' --tests '*CodexReviewConfigurationTest'
./gradlew clean build
```

Fixtures exercise PostgreSQL concurrency/restart/permit fencing, ambiguous sends,
deferred commit failure after remote acceptance, cap enforcement, loop filtering,
revocation and unsupported capability. Local HTTP fixtures exercise identity/scope,
readiness/SHA, fixed request shape, response/body deadlines, redirects and redaction.
They do not prove live OAuth provenance, production entitlement/accounting/budgets,
Codex acceptance, actual reviewed SHA, deployment or an end-to-end event loop.

V8 is additive and does not rewrite existing cycles, findings, commands or evidence.
Before enabling any future planner, reconcile pre-existing external review activity;
V8 does not fabricate tombstones for old requests. Roll back by disabling the
adapter and returning to the preceding application version while retaining V8 rows.
Do not remove reservations during rollback, retention cleanup or restoration: loss
of those rows loses deduplication history. Schema removal requires a separately
reviewed recovery plan. Privileged database users can still alter rows; immutable
audit and production backup/retention policy remain outside this increment.

Verification record for this increment (2026-11-17, America/Costa_Rica): base
`2167961917ff98178403abfaf342758e56b75106`; focused command above passed 20 tests
before adding the deferred-commit case. The final `./gradlew clean build` passed
all 196 tests (including 21 request-adapter tests), with zero failures/errors/skips.
The first full build exposed the old dispatch fixture's explicit table cleanup;
adding V8's table to that cleanup resolved its foreign-key failures. No live
credential, GitHub write, Codex invocation or production rollout was used for
verification.
