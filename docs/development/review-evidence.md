# Inline review evidence (issue #16)

## Implemented boundary

`GitHubReviewNormalizer` converts a complete, authorized GitHub GraphQL response into provider-neutral `ReviewEvidence.Snapshot` data. `IngestReviewEvidence` authorizes the installation/repository inside the PostgreSQL transaction and persists the snapshot, immutable source metadata and conservative inline finding occurrences atomically. The use case is available as an internal Spring bean. No HTTP ingestion endpoint, GitHub API fetcher, automatic polling, event planner or provider command is enabled by this increment. Webhook ingress continues to store routing metadata; approved workers must fetch authoritative review state before using this adapter.

This implements normalization and durable ingestion, not live webhook-to-review automation. A snapshot cannot advance the PR lifecycle, supply a clean verdict, spend a provider budget or verify a fix. Issue #17 extends this boundary with [general-feedback classification, findings and audited dispositions](general-feedback.md). Non-empty review bodies and PR issue comments now require explicit disposition; they cannot imply a clean verdict.

## Trusted caller contract

The caller must authorize its GitHub read using the paired installation/repository gate and an appropriately scoped credential. Pass an existing review-cycle UUID, its full head SHA, the expected repository/PR identity, a durable observation UUID, and the time the authoritative read **started**. Reuse these values on persistence retries. A new read gets a new UUID and capture time. Never use webhook arrival time as proof of source freshness. Capture time is trusted internal metadata, not PR-author input. It is normalized to PostgreSQL microsecond precision.

The normalizer requires `data.repository.databaseId`, `pullRequest.number` and `headRefOid` to match the requested scope/head. GraphQL errors, missing required metadata, duplicate IDs, duplicate JSON keys, trailing JSON, invalid SHAs/positions and responses over 2 MiB are rejected without retaining parser/provider diagnostics. Both outer connections and every comments connection require `pageInfo.hasNextPage = false`. The 2,000-source limit includes reviews, threads and comments. An approved reader must gather all pages, preserve every node and validate head/authorization again when assembling a multi-page read. An incomplete read is an error, never evidence of cleanliness. The adapter currently consumes assembled snapshots; it does not implement pagination or network requests.

For an older review round, call `normalizeForRound` with the round's SHA and the expected head of the current read. The normalizer verifies `headRefOid` against the expected observed head. The existing cycle foreign key uses the round SHA. V11 persists the observed head and includes it in the fingerprint when it differs from the round, preserving exact retries of earlier same-head snapshots. Both heads must match the reviewed SHA for a clean outcome.

Select these GraphQL fields (see [GitHub's GraphQL reference](https://docs.github.com/en/graphql/reference)):

```graphql
repository(owner: $owner, name: $name) {
  databaseId
  pullRequest(number: $number) {
    number headRefOid
    comments(first: 100) {
      pageInfo { hasNextPage }
      nodes { id body updatedAt author { login } }
    }
    reviews(first: 100) {
      pageInfo { hasNextPage }
      nodes { id state body updatedAt author { login } commit { oid } }
    }
    reviewThreads(first: 100) {
      pageInfo { hasNextPage }
      nodes {
        id isResolved isOutdated path line originalLine
        comments(first: 100) {
          pageInfo { hasNextPage }
          nodes {
            id body updatedAt author { login } path line originalLine
            commit { oid } originalCommit { oid }
            pullRequestReview { id } replyTo { id }
          }
        }
      }
    }
  }
}
```

The checked fixture is synthetic, shaped after these fields, and does not demonstrate a live GitHub query. A `null` `commit`, `originalCommit` or `pullRequestReview` (for example after a force-push) is recorded as an unknown SHA/review on that source only; `Source.staleFor` treats it as stale and the PR head is never substituted. Missing fields still reject the whole snapshot. Deleted authors may have a null login. Threads themselves have no reviewed SHA or body; those fields remain null, including for empty threads.

## Identity, provenance and freshness

- `review_evidence_snapshots` binds each observation to a cycle's installation, repository, PR and head. A length-prefixed canonical fingerprint rejects UUID reuse with changed content. Exact retries return `false`; newly committed observations return `true`. Different observations with the same PR/provider/capture time conflict because their order is ambiguous.
- `review_evidence_sources` retains every supplied review, thread and comment revision: provider node IDs, review/thread/reply relationships, author login, source update time, review state, file/line/original line, severity and SHA-256 of the body. Bodies and private diffs are neither persisted nor logged. A hash detects revisions; it cannot reconstruct deleted text. `github-node:<id>` references remain on finding occurrences. Provider is `github`; the source author preserves reviewer attribution, without claiming Codex billing identity.
- Each root inline comment is a **candidate** finding with one stable `finding_identities.id`, scoped by installation/repository/PR/provider/source kind/comment ID. Replies and empty threads remain evidence only. General summaries/comments follow the separate issue #17 contract. Severity defaults to `UNKNOWN`; an optional explicit classification map supplies severity by comment node ID. No prose, approval or “looks good” text can set a disposition. Classification is advisory data for future policy.
- `findings` keeps one occurrence per cycle/source and links it to the stable identity and latest source snapshot for that occurrence. Reusing the same comment across rounds shares identity; edits do not create new findings. New comments, even with identical text or a shared path, remain separate candidates. Semantic correlation of distinct provider comments is deliberately unsupported.
- `findings.reviewed_sha` is the **round's** head (the existing cycle foreign-key contract). The linked source's `reviewed_sha` is the actual comment/review commit, and `original_sha` is the original comment commit. Never use the round's SHA as proof the source reviewed that head. `Source.staleFor(head)` also considers GitHub's `isOutdated` flag. Thread observations have no commit and cannot prove freshness.

PR transaction locks serialize writes across cycles. Older captured snapshots remain in history and cannot overwrite a newer finding projection. Cross-cycle, cross-PR and cross-installation links are checked by composite foreign keys. There is no new current-head authority: compare with authoritative PR state before any later decision. Prior-round occurrences retain their historical resolution signal; consult the latest thread observation across rounds for current resolution.

## Resolution, edits and absence

`thread_resolved` and `thread_outdated` are independent source observations. A newer root-comment observation updates resolution/severity and links the occurrence to the new snapshot. Only when the comment revision changed (body hash or `updatedAt` differs from the linked source) does it conservatively reset the occurrence to `OPEN` and clear disposition/fix evidence; an unchanged re-read keeps the existing disposition. Legacy occurrences without a linked snapshot are reset on first observation. Only a separate future verification/disposition workflow may assert `VERIFIED_FIXED`. Identical persistence retries leave dispositions untouched. Resolving a thread, approving/dismissing a review, changing a body, deleting a comment, or receiving a reply does not prove a fix.

Absence from a later snapshot does not imply deletion, dismissal or a clean review. The old source revisions and candidate remain. Empty threads still retain their observed resolution/outdated state separately; they do not rewrite the missing root's historical occurrence. There is no authoritative deletion/tombstone API in this increment, and metadata-only history cannot recover text removed on GitHub. Production retention, deletion and encrypted storage policy must be established before rollout.

Inspect provenance for a round (authorize access outside this operator query):

```sql
SELECT f.stable_finding_id, f.review_cycle_id, f.source_id, f.thread_id,
       f.reviewed_sha AS round_head_sha, coalesce(o.observed_head_sha, o.head_sha) AS observed_head_sha,
       s.reviewed_sha AS source_reviewed_sha,
       s.original_sha, s.body_sha256, s.source_updated_at,
       s.thread_resolved, s.thread_outdated, f.severity, f.status, o.captured_at
FROM findings f
JOIN review_evidence_sources s
  ON s.snapshot_id = f.evidence_snapshot_id AND s.source_id = f.source_id
JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
WHERE f.installation_id = :installation AND f.repository_id = :repository
  AND f.pr_number = :pr
ORDER BY o.captured_at, f.source_id;
```

## Migration, verification and rollout

V6 adds three metadata tables, composite scope/provenance references and nullable links on legacy `findings`. V1–V5 remain unchanged. Existing findings retain their IDs, source, severity and disposition without inventing an observation, stable identity or current PR verdict. Roll back application code while retaining the additive schema; dropping V6 tables/columns destroys evidence and is not an automatic rollback.

Focused verification:

```sh
./gradlew test --offline --tests '*GitHubReviewNormalizerTest' --tests '*ReviewEvidenceIntegrationTest' --tests '*DatabaseIntegrationTest'
```

Tests cover metadata provenance and edits, stale/outdated threads, resolution before a fix, explicit severity, replies, multiple rounds, out-of-order snapshots, conflicting/concurrent retries, recreated-store persistence, scope/SHA mismatches, revoked authorization, deferred commit rollback, empty threads, missing sources and V5→V6 migration retaining legacy findings. PostgreSQL uses the pinned disposable Testcontainers image. Full build: `./gradlew clean build --offline`.

Live API reads, webhook reconciliation, deployment load, production data retention, semantic prose classification, fix verification and automatic lifecycle integration remain unverified or unimplemented as stated above.
