# ADR 0005: Durable findings and per-head-SHA review evidence

**Status:** Accepted (persistent PR state and SHA-bound clean evidence implemented; internal inline finding normalization implemented; general-feedback tracking/dispositions and stage guards implemented; live provider correlation and fix verification pending)
**Date:** 2026-11-13

## Context

GitHub has resolvable inline threads and general comments without a Resolve button. A clean comment can become stale after a new commit.

## Decision

Normalize all actionable feedback as durable `Finding` records with provenance (provider, comment/thread/review), severity, disposition and originating SHA. General comments receive a local finding ID and explicit disposition mechanism. A resolved GitHub thread is only one signal and does not prove the code is fixed. Bind clean verdicts to reviewed head SHA; invalidate/refresh after relevant updates.

## Consequences

More robust state and audit, but requires comment classification and human overrides for ambiguous prose.

## Alternatives

Treat all comments as threads: misses general feedback. Accept resolved checkbox as fixed: unreliable. Treat all bot “LGTM” as permanent: allows stale approvals.

## Verification

General-only feedback; deleted/edited comment; no-finding review; thread resolved before code changed; force-push invalidating a previously clean result.

Issue #15 implements the [persistent local review state contract](../development/review-state-machine.md), including guarded transitions, version/key rejection, pause/resume and invalidation of both clean stage SHAs after every head change. Existing cycle/finding schema and provenance are preserved. This does not itself prove or ingest live provider results or finding dispositions.

Issue #16 implements [durable internal inline normalization](../development/review-evidence.md): metadata-only review/thread/comment revisions, per-round occurrences and stable scoped source identities. Explicit severity and thread resolution are preserved without inferring a fix or clean verdict. Source reviewed/original SHAs remain distinct from the cycle head. General-feedback classification and live API/event integration remain separate work.

Issue #17 implements [general-feedback tracking, conservative classification and audited internal dispositions](../development/general-feedback.md). Exact-revision/head decisions govern clean-stage guards; new pending evidence atomically revokes clean readiness. Fix claims retain commit evidence without asserting verification. Author authentication endpoints and live reconciliation remain separate integrations.

Issue #19 adds [internal outcome classification and version-bound clean recording](../development/review-outcomes.md). The `AssessReviewOutcome.recordClean` path requires a correlated, completed, structured no-findings result for the exact provider, stage and SHA. PostgreSQL checks request, cycle and snapshot correlation and pending findings before atomically recording clean provenance. The legacy `ReviewLifecycle.apply` API retains its trusted internal caller contract and can record clean transitions without this correlated evidence. Provider integrations should use `AssessReviewOutcome`. Live Codex integration still requires an adapter that authenticates completion and gathers current remote evidence.
