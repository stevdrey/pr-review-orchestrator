# Conservative review outcomes

Implements [issue #19](https://github.com/stevdrey/pr-review-orchestrator/issues/19) under ADRs [0004](../adr/0004-deterministic-policy-and-budget-gates.md), [0005](../adr/0005-finding-state-and-head-sha.md) and [0006](../adr/0006-provider-neutral-agents-and-events.md). Classification and clean recording are internal Java use cases backed by PostgreSQL. No live review loop or verified Codex completion transport is enabled.

## Inputs and trust

`ReviewOutcomeClassifier` is pure policy over immutable metadata. The `AssessReviewOutcome` Spring bean delegates to `ReviewOutcomeStore`; `JdbcReviewOutcomeStore` authorizes inside a bounded independent transaction and serializes assessment/clean recording with evidence ingestion, finding dispositions and lifecycle updates under the existing PR lock.

The caller supplies a `ReviewOutcome.Assessment`:

- `Run`: installation/repository/PR, head SHA, review provider, baseline/deep stage, request UUID, cycle UUID, evidence provider and expected reviewer identity. For Codex evidence on GitHub, `provider=codex` and `evidenceProvider=github` remain distinct.
- The aggregate version captured when gathering that evidence; never refresh a delayed command's version to make old evidence pass.
- `Completion`: the provider run, exact review source ID, explicit status and structured conclusion. Only an approved adapter with an independently verified completion/correlation contract may supply `NO_FINDINGS`. A successful request POST, comment receipt, reaction, GitHub `APPROVED` state or “LGTM” text does not establish this conclusion. An unsupported contract must supply `UNSTRUCTURED`/`UNKNOWN`; pending, failure and timeout remain explicit.
- The persisted snapshot UUID, or null when evidence is unavailable.

Completion authentication and expected reviewer identity are trusted adapter inputs. The current evidence schema retains reviewer login, not a verified numeric provider identity; a future adapter must establish identity independently before supplying the expected reviewer. Never build these inputs from untrusted comment instructions, claimed webhook fields, caller-supplied login or model output. No public HTTP/MCP endpoint, credential, provider invocation, execution privilege or spending authority is added.

## Durable correlation

The store checks the authoritative local aggregate's version/head, then joins the actual request and cycle on installation/repository/PR/head/provider/stage. The request must have a recorded comment receipt (`COMMENT_CREATED` or `COMMENT_CREATED_HEAD_UNCONFIRMED`); reserved, attempted, unsent or absent requests cannot establish clean evidence. A receipt remains distinct from provider completion. The completed source's exact reviewed SHA must independently match even for a head-unconfirmed request.

Evidence is loaded from the persisted snapshot and sources rather than supplied inline by the caller. It must belong to the exact cycle/PR/head/evidence provider and be the uniquely latest observation of that PR/evidence provider across rounds. Its round SHA and observed head must both match the reviewed SHA. A newer observation or legacy equal-time ambiguity makes an old reference unknown. The snapshot's PostgreSQL-assigned `ingested_at` cannot predate the request and must be strictly later than the `UPDATE_HEAD` or `CONFIRM_HEAD` transition with the greatest `resulting_version`. V9 assigns both `ingested_at` and new transition timestamps with `clock_timestamp()` when the writes occur under the same PR lock. This accounts for transactions that start in a different order from the writes. The caller-supplied `captured_at` only orders observations of the same reader. Snapshots without `ingested_at` (pre-V9) are unknown. The completed review's GitHub `updatedAt` is an additional check against the request and head-observation times and is subject to clock skew. Returning to an earlier SHA cannot revive an old review by refreshing the command version.

The original request epoch must also match the current [request epoch](fix-correlation.md). A same-head confirmation or a return to an earlier SHA opens a new epoch. A completion tied to a prior epoch cannot establish clean evidence, even when retrieved after the latest head observation. V13 associations support deduplication. They supply insufficient ordering evidence for a clean outcome because pre-V9 transitions used transaction-start timestamps and existing rows lack ordering provenance.

Retained pending findings are read across rounds in the same transaction. Latest open or acknowledged inline findings block even when absent from the current snapshot. This rule lives in the shared pending-findings guard, so lifecycle clean/readiness transitions and ingestion-time revocation enforce it as well. Resolved and outdated flags do not clear them. An inline candidate visible in the current snapshot stops blocking only when its finding occurrence linked to that exact snapshot is `DISMISSED` or `VERIFIED_FIXED`; general dismissals never clear inline candidates. Each new cycle projects a fresh `OPEN` occurrence, so a visible comment must be closed again for that round. For general feedback, the store applies the same audited dismissal rules to retained findings and to candidates in the current snapshot. The latest decision must dismiss the exact source, body revision and assessment at the current head. A dismissal applies to that general feedback only. It can clear an ambiguous (`NEEDS_HUMAN`) body on the completed review itself, but never an `ACTIONABLE` completion body, which contradicts a `NO_FINDINGS` conclusion. The snapshot stays intact for completion correlation and provenance. Legacy pending findings still block.

Callers must ingest complete, current authorized API reads. The GitHub normalizer rejects partial/error/paginated reads; no favorable-source selection, invented commit SHA or unparsed body labeled `Feedback.NONE` is permitted. These local checks cannot detect an unobserved remote edit/push or authenticate a provider completion. Live fetching and provider capability verification remain separate integrations.

### Authenticated Codex completion

[Issue #74](https://github.com/stevdrey/pr-review-orchestrator/issues/74) adds the internal `assessCodex` and `recordCodexClean` methods. They resolve the exact committed GitHub request receipt, then consult an optional deployment-approved `CodexCompletionSource`. The source must authenticate the provider record or verify a supported join, establish the provider review identity, and return the uniquely current terminal execution and attempt for the exact comment. The terminal record must identify the full reviewed SHA, its outcome and the exact GitHub review revision. The local request and cycle establish the stage. An optional provider stage can be mapped only if the supported source establishes its relationship to the durable request.

The store rechecks the scope, comment, SHA, stage, request visit, cycle and current snapshot under the PR lock. It matches the review source ID, author, body hash and update time. The provider's terminal time must fall between the request creation and the evidence capture, with a 30-second tolerance for clock skew. The exact comment, attempt, SHA and revision binding remains the proof. An edited source invalidates the earlier proof until an authenticated record binds the new revision. A different request on the same SHA needs its own receipt and attempt. A planned baseline can advance only through the outcome boundary for its own request. V18 stores the trigger comment ID, execution ID, attempt ID and terminal time on the accepted clean transition and prevents reuse of an attempt as separate clean evidence.

The positive integration fixture authenticates a synthetic terminal record with a fixture key. It also exercises bad authentication, summary edits, alternate requests for the same SHA, a stale reviewed SHA, stage mismatch and exact replay. The application registers no native Codex completion source. With no approved source or missing or conflicting evidence, the planned path stays closed to a clean transition and still reports known retained blockers.

### PR #35 correlation replay

The [isolated OAuth run](../adr/0007-one-shot-sandbox-codex-authorization.md#observed-result) recorded request `1b416560-e011-4113-a23a-56486497574e` for `BASELINE` and `42017af3b3957157e707c9c6e72c5b44c24051e4`. The durable record links its request UUID to GitHub comment `7184384751`. The Codex App's eyes reaction `484484746` directly acknowledges that comment. [Request](https://github.com/stevdrey/realtime-voice-lab/pull/35#issuecomment-7184384751)

The Codex App edited summary `7182106453` to report `code_review.status=completed` for the full SHA. A separate Codex receipt `7184425421` gives `42017af3b3`, which GitHub resolves to that commit. Both are conversation comments, and the PR reviews endpoint returned zero formal reviews. The summary was originally created for an earlier review and was edited in place. Its current metadata contains the reviewed SHA, status and finding flags. The receipt marker has an empty payload. Neither comment nor the timeline carries an authenticated reference to the request or a provider run ID that joins acknowledgment to completion. [Summary](https://github.com/stevdrey/realtime-voice-lab/pull/35#issuecomment-7182106453) · [Receipt](https://github.com/stevdrey/realtime-voice-lab/pull/35#issuecomment-7184425421) · [Reviewed commit](https://github.com/stevdrey/realtime-voice-lab/commit/42017af3b3957157e707c9c6e72c5b44c24051e4)

A later read-only check found the same Codex summary and receipt without a request or run identifier. All five formal review submissions returned by GitHub belonged to the human reviewer. The historical acknowledgment is recorded in the one-shot run. [PR #35](https://github.com/stevdrey/realtime-voice-lab/pull/35)

The sequence supports an association with the one observed request. The four check runs at that commit include no Codex record, and the commit status endpoint returned no entries. A previous read-only Codex Cloud lookup found no matching task among the accessible tasks. The public OpenAI GitHub review guide documents the request, reaction and review. It does not document an identifier that joins the exact comment to the native terminal attempt. OpenAI's compliance guide describes workspace audit access, but a supported record or export for this run has not been established. [Existing lookup and requested fields](https://github.com/stevdrey/pr-review-orchestrator/issues/23#codex-execution-id-lookup-retry) · [GitHub review guide](https://learn.chatgpt.com/docs/third-party/github) · [Compliance API](https://learn.chatgpt.com/docs/enterprise/compliance-api)

Exact completion correlation in [issue #23](https://github.com/stevdrey/pr-review-orchestrator/issues/23) remains open. A matching SHA, timing and provider login are insufficient to select a request across summary edits or multiple runs on one head. The PR #35 evidence still cannot authorize a clean transition. No new review was sent and per-review cost is outside this work.

The local `sandboxPr35SummaryAndReceiptCannotEstablishExactBaselineCompletion` regression uses a reconstruction of those comment shapes. The normalizer retains them as general feedback. The classifier returns `MISSING_COMPLETION` with no completion input, and `MISSING_REVIEW` if either comment ID is supplied as the completion source, with no progress evidence in either case. The fixture isolates correlation from the independent pending-feedback gate. No live state was updated during the replay.

## Decisions

The result retains the run, aggregate version, a verdict and stable reason code. It is a stage result, not an assertion of full correctness.

| Evidence | Verdict |
| --- | --- |
| Retained actionable findings | `ACTION_REQUIRED` |
| Retained ambiguous feedback | `NEEDS_HUMAN` |
| Missing/uncorrelated durable request/cycle/snapshot, unknown findings, missing completion, provider pending/failure/timeout | `UNKNOWN` |
| Missing completed review, wrong/deleted reviewer, missing/stale reviewed SHA, pending/dismissed/unsupported review state | `UNKNOWN` |
| Correlated completed review with inline candidates, actionable general feedback, `CHANGES_REQUESTED` or structured findings | `ACTION_REQUIRED` |
| Correlated completed review with ambiguous general feedback or unstructured conclusion | `NEEDS_HUMAN` |
| Unknown conclusion or source revision newer than the observation | `UNKNOWN` |
| Correlated completion, exact SHA, supported final review state, structured no-findings conclusion and no pending/candidate feedback | `CLEAN_FOR_SHA` |

After durable correlation, a completed review with `CHANGES_REQUESTED` or structured `FINDINGS` takes precedence over ambiguous general feedback. If completion or durable correlation is unavailable, known retained blockers still determine the outcome (`ACTION_REQUIRED`/`NEEDS_HUMAN` rather than `UNKNOWN`). Generic approval cannot override pending general feedback. An empty snapshot produces `UNKNOWN`. A stale follow-up cannot supply the reviewed commit or replace another stage's completion.

## Recording and inspecting clean evidence

`AssessReviewOutcome.recordClean(assessment, commandId, gates)` reloads and reclassifies the persisted evidence in the same transaction that journals the clean transition. It accepts only `CLEAN_FOR_SHA`: baseline produces `BASELINE_CLEAN`, deep produces `DEEP_CLEAN`. Unknown/ambiguous/actionable results cannot advance. Existing version/head, legal-state, authorization, eligibility and general-feedback guards remain mandatory. Evidence arriving between an earlier assessment and this commit blocks or supersedes the earlier reference. A separate readiness transition still requires both clean stages, current CI explicitly passed and policy gates.

V9 adds `review_clean_evidence`, atomically linking each accepted transition to its exact request, cycle, snapshot, source review, reviewer, provider, stage and SHA. Composite foreign keys prevent scope mismatches. Reuse the command UUID on retry: exact committed retries return `DUPLICATE_TRANSITION`, including after restart/later transitions; changed proof or reuse of a lower-level lifecycle command UUID returns `TRANSITION_ID_REUSED`. Failed commits retain neither state/journal updates nor clean evidence. Classification itself is derived, not journaled.

```sql
SELECT provider, stage, head_sha, request_id, cycle_id, snapshot_id,
       review_source_id, reviewer, transition_id, recorded_at
FROM review_clean_evidence
WHERE installation_id = :installation AND repository_id = :repository AND pr_number = :pr
ORDER BY recorded_at;
```

The lower-level lifecycle API remains a trusted internal API for existing callers and rejects direct clean transitions for planned Codex baselines. Provider adapters should use this assessment boundary: an assessment result exposes no public command for a clean transition, and only `recordClean` journals clean evidence. Nonclean classification does not automatically pause, schedule retries, dismiss findings or revoke an earlier clean stage: its caller must stop advancement and reconcile evidence. Existing ingestion still revokes readiness on new pending general feedback. No notification, merge or automatic provider invocation occurs.

## Migration, verification and limits

V9 is additive and preserves V1–V8 checksums. It does not infer provenance for existing clean fields; those legacy internal decisions receive no invented evidence rows. Back up before migrating. Stop new callers before rolling application code back, preserve the added schema/evidence, and use a reviewed forward correction rather than deleting audit history. Older application code retains its existing trusted caller contract and does not enforce the new assessment boundary.

```sh
./gradlew test --offline --tests '*ReviewOutcomeClassifierTest' --tests '*GitHubReviewNormalizerTest' --tests '*ReviewStateIntegrationTest' --tests '*GeneralFeedbackIntegrationTest'
./gradlew clean build --offline
git diff --check
```

Tests cover provider failures/pending/timeout, generic “LGTM”, stale commits/follow-ups, wrong PR/provider/stage/request/cycle/reviewer, unsent and pre-request evidence, missing/contradictory evidence, resolved/outdated and retained missing inline roots, clean provider/stage/SHA provenance, CI guards, exact/conflicting retries, authorization revocation, force-push away/back, newer evidence after assessment, migration compatibility and deferred commit rollback. PostgreSQL tests use disposable containers, without GitHub/provider credentials.

Live Codex completion retrieval/authentication, provider identity verification, GitHub fetching/reconciliation, deployed behavior and production activation remain unverified. Without a supported structured completion contract, actual Codex prose stays unstructured/unknown and cannot establish clean evidence through this boundary. The default-disabled request path and its OAuth/accounting/budget prerequisites are unchanged.

Local verification on 2026-11-17 (America/Costa_Rica), JDK 27 and disposable PostgreSQL: the focused command passed with 70 tests after review fixes; `./gradlew clean build --offline` passed with **230 tests, 0 failures, 0 errors, 0 skipped**. `git diff --check` and relative Markdown link validation passed. These checks do not establish live provider behavior or deployment.

Head-update ordering verification on 2026-11-18 (America/Costa_Rica). The two regression tests reproduced incorrect verdicts on `dfe852f` and passed with the fix in `./gradlew test --offline --tests '*ReviewStateIntegrationTest'`. The full `./gradlew clean build --offline` passed with **232 tests, 0 failures, 0 errors, 0 skipped**. A disposable database that already applied an earlier draft of V9 needs a fresh migration run because the draft checksum changed.
