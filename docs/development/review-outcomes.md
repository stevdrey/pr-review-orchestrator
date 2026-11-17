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

Evidence is loaded from the persisted snapshot and sources rather than supplied inline by the caller. It must belong to the exact cycle/PR/head/evidence provider and be the uniquely latest observation of that PR/evidence provider across rounds. A newer observation or legacy equal-time ambiguity makes an old reference unknown. Snapshot and completed review source must postdate the request and the most recent local head update; returning to an earlier SHA cannot revive an old review by refreshing the command version.

Retained pending findings are read across rounds in the same transaction. Latest open/acknowledged inline findings block even when absent from the current snapshot; resolved/outdated flags do not clear them. General feedback uses the existing revision/head-bound audited dismissal rules, distinguishing actionable from ambiguous pending evidence. Legacy pending findings remain conservative. The classifier independently checks all current snapshot candidates too; it does not suppress general bodies or inline roots based on a disposition, so such raw snapshots may still require reconciliation even after a valid human dismissal.

Callers must ingest complete, current authorized API reads. The GitHub normalizer rejects partial/error/paginated reads; no favorable-source selection, invented commit SHA or unparsed body labeled `Feedback.NONE` is permitted. These local checks cannot detect an unobserved remote edit/push or authenticate a provider completion. Live fetching and provider capability verification remain separate integrations.

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

Known retained blockers take precedence over unavailable completion after durable correlation. Generic approval cannot override general feedback. An empty snapshot is unknown: absence of a completed review is not a no-findings result. A stale follow-up cannot supply the reviewed commit or replace another stage's completion.

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

The lower-level lifecycle API remains a trusted internal API for existing callers; provider adapters should use this assessment boundary. Nonclean classification does not automatically pause, schedule retries, dismiss findings or revoke an earlier clean stage: its caller must stop advancement and reconcile evidence. Existing ingestion still revokes readiness on new pending general feedback. No notification, merge or automatic provider invocation occurs.

## Migration, verification and limits

V9 is additive and preserves V1–V8 checksums. It does not infer provenance for existing clean fields; those legacy internal decisions receive no invented evidence rows. Back up before migrating. Stop new callers before rolling application code back, preserve the added schema/evidence, and use a reviewed forward correction rather than deleting audit history. Older application code retains its existing trusted caller contract and does not enforce the new assessment boundary.

```sh
./gradlew test --offline --tests '*ReviewOutcomeClassifierTest' --tests '*GitHubReviewNormalizerTest' --tests '*ReviewStateIntegrationTest' --tests '*GeneralFeedbackIntegrationTest'
./gradlew clean build --offline
git diff --check
```

Tests cover provider failures/pending/timeout, generic “LGTM”, stale commits/follow-ups, wrong PR/provider/stage/request/cycle/reviewer, unsent and pre-request evidence, missing/contradictory evidence, resolved/outdated and retained missing inline roots, clean provider/stage/SHA provenance, CI guards, exact/conflicting retries, authorization revocation, force-push away/back, newer evidence after assessment, migration compatibility and deferred commit rollback. PostgreSQL tests use disposable containers, without GitHub/provider credentials.

Live Codex completion retrieval/authentication, provider identity verification, GitHub fetching/reconciliation, deployed behavior and production activation remain unverified. Without a supported structured completion contract, actual Codex prose stays unstructured/unknown and cannot establish clean evidence through this boundary. The default-disabled request path and its OAuth/accounting/budget prerequisites are unchanged.

Local verification on 2026-11-17 (America/Costa_Rica), JDK 27 and disposable PostgreSQL: the focused command passed with 65 tests; `./gradlew clean build --offline` passed with **225 tests, 0 failures, 0 errors, 0 skipped**. `git diff --check` and relative Markdown link validation passed. These checks do not establish live provider behavior or deployment.
