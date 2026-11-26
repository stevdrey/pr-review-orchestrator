# ADR 0007: One authorized Codex request in the PR #35 sandbox

**Status:** Accepted for one sandbox invocation. Authorization consumed.

**Date:** 2026-11-26

## Context

Issue [#23](https://github.com/stevdrey/pr-review-orchestrator/issues/23) needs direct evidence that the orchestrator can request a native review through a consented GitHub App user credential and observe the provider's response. The repository owner explicitly authorized a single request on [realtime-voice-lab PR #35](https://github.com/stevdrey/realtime-voice-lab/pull/35), head `42017af3b3957157e707c9c6e72c5b44c24051e4`, accepting unknown consumption. A verified provider usage bound remains pending under [issue #28](https://github.com/stevdrey/pr-review-orchestrator/issues/28).

## Decision

Authorize one `SANDBOX_OPERATOR` invocation by the orchestrator, through a dedicated private GitHub App restricted to that repository and a fresh OAuth grant from the repository owner. An isolated local launcher uses the app's existing OAuth, review trigger, durable request store and fixed GitHub comment adapter. Bind the operator, installation, repository, PR, full head and initial request epoch. Consume a local one-time permit before calling the provider and retain the durable request receipt or tombstone. Do not retry after that point, including after an ambiguous outcome.

This invocation is a narrow exception to the individual accounting and financial reservation prerequisites of [ADR 0002](0002-github-app-webhook-trust.md) and [ADR 0004](0004-deterministic-policy-and-budget-gates.md). The regular application continues to require trusted approval and budget terms. Do not enter synthetic usage or capacity in the live test. Use an isolated database with no webhook, background workers or notifications. Authorization expires with this invocation or if the target SHA changes.

## Consequences

The request may consume Codex allowance or credits in an unknown amount. The one-attempt permit limits invocations. GitHub comment authorship, Codex trigger acceptance, reviewed SHA and usage attribution are separate observations. The GitHub API permits a head change between the preflight read and comment creation, so the response and any authoritative completion evidence must be checked for the actual SHA. Close the local grant after the invocation and revoke the remote OAuth grant and temporary repository access after evidence collection.

## Alternatives

Wait for provider accounting evidence or limit validation to a read-only OAuth probe. Both leave live trigger acceptance unobserved for this authorized sandbox head.

## Verification

Check the open PR and full head before the invocation. Record the durable request disposition, GitHub comment ID and actor if created, and the provider response with its actual SHA if available. Report usage as unknown and any missing correlation explicitly. Keep the PR open and perform no approval or merge.

## Observed result

On 2026-11-26, the launcher applied all 17 migrations to disposable PostgreSQL. The orchestrator obtained a fresh consented OAuth grant, consumed the local one-time permit and recorded one durable request, `1b416560-e011-4113-a23a-56486497574e`. The final state was `COMMENT_CREATED` for `BASELINE`, actor ID `3165632` and comment ID `7184384751`. At 06:23:35 UTC, GitHub attributed the exact `@codex review` comment to `stevdrey` via `stevdrey-rvl35-oauth-probe`. [Request](https://github.com/stevdrey/realtime-voice-lab/pull/35#issuecomment-7184384751)

At 06:23:45 UTC, the Codex GitHub App added an eyes reaction directly to that comment. At 06:23:48 UTC, it edited its existing summary to `running` for the authorized SHA. At 06:26:07 UTC, the same summary reported `completed`, with no findings, for `42017af3b3957157e707c9c6e72c5b44c24051e4`. The completed summary carries no request-comment ID. The reaction authenticates acknowledgment of the exact request, and the summary records completion at the exact SHA. The app did not promote that summary to provider completion evidence under its strict request-correlation contract. [Codex summary](https://github.com/stevdrey/realtime-voice-lab/pull/35#issuecomment-7182106453)

The app made one request without a retry. The PR remained open at the authorized SHA. The sandbox stopped with the metadata-only request record preserved locally. Device Flow was disabled, the temporary installation was removed and the operator's individual consent was revoked. Cost measurement was outside the run.
