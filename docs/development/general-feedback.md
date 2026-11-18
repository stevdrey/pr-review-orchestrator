# General PR feedback (issue #17)

`GitHubReviewNormalizer` now accepts complete PR issue comments and review bodies alongside inline evidence. `IngestReviewEvidence` retains their metadata and stable local finding IDs. `DisposeGeneralFeedback` provides audited internal author/maintainer decisions. Pending general feedback deterministically blocks `BASELINE_CLEAN`, `DEEP_CLEAN` and `MARK_READY_FOR_HUMAN`, even when the caller's gates say there are no blocking findings.

This is an internal application contract. Live GitHub fetching, webhook reconciliation, authenticated author-facing HTTP/MCP actions, fix verification, notifications and provider orchestration remain separate integrations. The default-disabled dispatcher stays disabled. No GitHub comment, thread resolution, model invocation or merge is performed.

## Capture and classification

Use the [review evidence caller contract](review-evidence.md): authorized complete authoritative read, existing cycle and full head SHA, durable observation UUID and trusted read-start time. In addition to the documented reviews and threads, select the PR's issue-comment connection:

```graphql
comments(first: 100) {
  pageInfo { hasNextPage }
  nodes { id body updatedAt author { login } }
}
```

`comments` is required, including when empty. Missing or unfinished pagination fails the whole normalization. The existing 2 MiB / 2,000-source limits apply to the combined snapshot. This consumes an assembled GraphQL response; the caller must gather pages and recheck authorization and head. Webhook `issue_comment` metadata by itself cannot establish freshness or provide a comment body.

Every nonblank general body is a local candidate. Without an explicit advisory actionable-ID classification it receives `NEEDS_HUMAN`; explicitly actionable IDs receive `ACTIONABLE`. Both block equally. Approval state, “LGTM”, arbitrary prose and prompt-injection instructions cannot establish cleanliness or disposition. New blank bodies remain observations only. Once a source has been tracked, blanking its body retains the finding and invalidates the prior revision's decision. Absence/deletion does not remove it. Escalation is visible as `NEEDS_HUMAN` plus the blocking finding, not an automatic notification.

Review bodies have kind `REVIEW`; PR issue comments have kind `ISSUE_COMMENT`. Neither has a thread or resolution flag. Issue comments have no reviewed commit: the source SHA remains null, independently of the round head. Identity is installation/repository/PR/provider/kind/source node ID, stable across edits and rounds. One general body currently maps to one finding; splitting prose or semantically merging separate comments is unsupported. Bodies are hashed and discarded. Source author, timestamps and all previous revisions remain available.

## Explicit disposition

The Spring bean `DisposeGeneralFeedback` accepts a PR identity and `GeneralFeedback.Disposition` containing a durable command UUID, stable finding UUID, latest finding snapshot UUID, expected current head SHA and aggregate version, action, authenticated actor, reason and optional fix commit. The trusted caller must authenticate the actor as the PR author or an authorized maintainer, derive the actor identity from that authentication, and fetch the authoritative current head before invoking it. Never construct this command by parsing comment prose or trusting a supplied username. There is no public user authentication/action endpoint in this increment.

| Action | Required evidence | Result |
| --- | --- | --- |
| `ACKNOWLEDGE` | Actor and nonblank reason | Audited `ACKNOWLEDGED`; still blocking |
| `CLAIM_FIXED` | Actor, reason and full commit SHA | Audited proposed commit; still `ACKNOWLEDGED` and blocking |
| `DISMISS` | Actor and nonblank reason | Explicit human decision; unblocks this revision at this head |

A fix claim never sets `VERIFIED_FIXED`, `verified_at` or a clean verdict. A later verifier must establish the actual fix and authoritative commit relationship; accepting a syntactically valid claim is not proof that GitHub contains that commit. Dismissal is an accountable human choice, not verification of correctness. Reasons are bounded to 4,096 characters and persisted in the disposition journal; avoid including credentials or private source text. No reason is sent to an AI provider or included in routine exception diagnostics.

Commands recheck live installation/repository authorization inside the transaction, including exact retries. They reject foreign/missing identities, stale head/version, a superseded snapshot, ambiguous latest observations and UUID reuse with altered content. An exact committed retry returns `false` and cannot undo a later action. A committed new action returns `true`. The journal retains actor, reason, source snapshot, current head/version, proposed commit and database time, with a sequence establishing action order. This is an application audit trail; privileged database roles can still modify it.

## Gates, ordering and readiness

A transaction-level PR lock serializes evidence ingestion, dispositions and lifecycle writes, including across review cycles. Equal capture times for new snapshots of the same PR/provider conflict. Older captures stay in history but cannot replace newer finding evidence. The gate checks the latest occurrence of every stable general finding across all rounds, including findings absent from the current snapshot. Legacy pending general findings without normalized evidence also block until reconciled. Legacy ties remain conservative: contradictory latest revisions cannot be disposed until a new authoritative observation resolves their order.

Every newly ingested snapshot also reconciles the latest retained revision per round for tracked general sources. If a blank revision arrives before an older nonblank read first establishes the finding, the newer blank revision is projected before commit. Dispositions therefore target the same revision regardless of arrival order; stale snapshots cannot become authoritative merely because they were processed last. The reconciliation is scoped to the PR/provider and uses the same transaction lock and revision-preserving upsert as regular projection. It requires no schema change.

Disposition errors preserve their established `retryable` value through transaction rollback and outer error sanitization. Deterministic persistence rejections, such as an embedded NUL in human metadata, remain non-retryable, leave no audit/projection changes and do not consume the command UUID. Transient/unknown commit failures remain retryable; neither path retains raw database diagnostics in the returned exception.

Only the latest audited action can unblock, and it must be `DISMISS` for the current head and the same body hash, source update time and assessment as the latest finding. An unchanged re-read can retain this decision. An edit, assessment change, new head or distinct same-head synchronize confirmation requires another decision. A dismissal must postdate the last `UPDATE_HEAD` or `CONFIRM_HEAD` transition so returning to an earlier SHA cannot revive an old decision. Finding status on a historical round is not gate authority. The audit/revision/head check is authoritative for general feedback. Inline disposition/verification rules are unchanged.

If new pending feedback arrives after a clean baseline or readiness, ingestion atomically clears both clean SHAs, increments the PR version and journals `BLOCKING_FINDINGS`. Active clean states become `ACTION_REQUIRED`; paused states stay paused and resume to `ACTION_REQUIRED`. A concurrent clean transition either fails its guard or is invalidated before ingestion commits. A dismissal does not automatically restore clean stages: policy and fresh review/CI evidence are still required. The same invalidation applies if a human replaces a dismissal with an acknowledgment or fix claim.

Inspect current/historical provenance and audited decisions under separately authorized operator access:

```sql
SELECT f.stable_finding_id, f.evidence_snapshot_id, f.source_kind, f.source_id,
       f.reviewed_sha AS round_head, s.reviewed_sha AS source_reviewed_sha,
       s.feedback, s.body_sha256, s.source_updated_at, o.captured_at, f.status
FROM findings f
JOIN review_evidence_sources s
  ON s.snapshot_id = f.evidence_snapshot_id AND s.source_id = f.source_id
JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
WHERE f.installation_id = :installation AND f.repository_id = :repository
  AND f.pr_number = :pr AND f.source_kind IN ('REVIEW', 'ISSUE_COMMENT')
ORDER BY o.captured_at DESC;

SELECT id, stable_finding_id, snapshot_id, head_sha, head_version, action, actor, reason,
       evidence_sha AS proposed_fix_sha, occurred_at
FROM general_feedback_dispositions
WHERE installation_id = :installation AND repository_id = :repository AND pr_number = :pr
ORDER BY disposition_order;
```

## Migration and verification

V7 extends source kinds/assessments, creates the scoped disposition journal and retains V6 fingerprint compatibility for exact persistence retries. Existing non-empty V6 review bodies receive conservative findings and revoke any already-clean aggregate; no reviewed SHA, decision or fix evidence is invented. For each tracked identity, migration projects the latest retained revision in every round, including blank-only rounds. Reviews that were always empty remain observations only. Provider is part of revision selection, and the scoped identity join prevents a blank observation from borrowing another provider's finding. Historical whitespace bodies are conservatively escalated because V6 did not retain text. Existing inline IDs, provenance and dispositions survive. V1–V6 migration checksums are unchanged.

The backfill correction revises V7 while it is still an unmerged PR migration, so its checksum changes. A development database that already applied an earlier PR revision of V7 requires a backup and controlled data/history reconciliation before accepting the revised migration. Automatic checksum repair is not supplied: `flyway repair` alone would not populate the omitted finding occurrences. Verify the corrected V6→V7 path against a fresh disposable database rather than discarding retained evidence.

Keep a database backup before applying migrations. Rollback must pause orchestration: older code does not enforce these general-feedback gates. Retain the additive schema and audit history when rolling application code back; dropping it destroys evidence. Production retention and privileged audit protection remain deployment work.

Focused verification:

```sh
./gradlew test --offline --tests '*GeneralFeedbackIntegrationTest' --tests '*GitHubReviewNormalizerTest' --tests '*PullRequestReviewTest'
```

Integration tests use disposable pinned PostgreSQL. They cover general-only actionable/ambiguous feedback, absence/blanking/edits, source/round SHA separation, stable identities, audited actions, proposed fixes remaining blocking, exact/conflicting/concurrent retries, authorization revocation, cross-PR rejection, head changes and return to an earlier SHA, store recreation, rollback on deferred commit failure, all clean-stage guards, paused readiness invalidation and V6 upgrade/retry compatibility. The cross-round V6→V7 regression uses frozen legacy fingerprints and actual disposition/lifecycle calls: migration alone must project the newer blank revision, exact retries must stay no-ops, obsolete dispositions must fail, and only a current-revision dismissal may unblock baseline review. It also covers a newer blank observation with the same source ID under another provider. Arrival-order regression cases cover review bodies and issue comments within and across rounds, including rejection of stale dispositions. Invalid actor/reason cases verify non-retryable persistence errors, rollback, redaction and UUID reuse after rejection. A race test covers ingestion against a clean transition. Full verification: `./gradlew clean build --offline`.

Live GitHub delivery and author authentication, actual fix verification, production scale/retention and deployed behavior have not been demonstrated by these local tests.

Implementation verification: `git diff --check` passed; `./gradlew clean build --offline` passed with **175 tests, 0 failures, 0 errors, 0 skipped** on JDK 27 using disposable PostgreSQL. Local Markdown link validation also passed. These results do not establish live or deployed behavior.
