# Integration contracts and capability verification

This document describes planned endpoints and adapter contracts. The [GitHub App authentication and installation authorization adapter](../github/app-setup.md) is implemented as an internal opt-in Java component; the [webhook receiver](../github/webhook-ingress.md) is a separate opt-in HTTP adapter.

## GitHub ingestion

Events to investigate: `pull_request` (opened, ready_for_review, synchronize, reopened, closed), `pull_request_review`, `pull_request_review_comment`, `pull_request_review_thread`, `issue_comment`, `check_run`, `check_suite`, `workflow_run`. GitHub delivery semantics and payload availability depend on app event subscription and permissions; verify each combination via captured **sanitized** fixtures.

Implemented ingress: `POST /webhooks/github`. Verify bounded raw HMAC-SHA256 bytes, delivery ID, installation, repository and explicitly supported event/action before queuing metadata. Return 202 after commit, 200 for a durable duplicate, or 204 for a signed ping that queues no work. See the [route and response contract](../github/webhook-ingress.md) for unsupported lifecycle events, replay fingerprints, secret rotation and metadata-only limitations. Detailed payloads are not retained; a future worker must fetch authoritative GitHub state.

Outbound GitHub: limited to app-authorized operations; use user-to-server authorization where necessary for account attribution and verify Codex subscription ownership in an integration test. GitHub comment author identity and Codex billable identity may differ.

The [Codex attribution spike](../spikes/issue-14-codex-attribution.md) records observed trigger behavior, attribution limits, the controlled test protocol and a fail-closed fallback. User-token and automatic triggers succeeded in the tested configuration; per-review usage attribution remains unverified and is tracked in [issue #42](https://github.com/stevdrey/pr-review-orchestrator/issues/42).

## ReviewProvider port

Proposed operations: `requestReview(ReviewRequest)`, `getReviewOutcome(ProviderReference)`, `summarizeFindings(ReviewContext)`. Providers can be async; commands must carry idempotency key, review stage, budget reservation and expected head SHA. No generic provider contract should require a human login or a fixed model name.

## AgentEventPublisher / MCP

An optional adapter may expose read-only PR status and controlled actions via MCP tools, and publish subscribed domain events to compatible platforms (including Dots/Work **only after a real capability test**). The application is event-driven regardless of MCP client availability.

Possible event kinds: `pr.review.completed`, `pr.feedback.detected`, `pr.feedback.resolved`, `pr.ci.failed`, `pr.ready.for.validation`, `pr.review.limit.reached`, `pr.human.approval.required`. Include event ID, repo/PR identifiers, SHA, trace ID and only redacted metadata. Apply replay/idempotency and opt-in subscription policies; do not send full source/diffs by default.

## NotificationPort

Notify PR author by email **only when an address and permission are legitimately available**; GitHub notification/comment is the fallback. Respect opt-in, privacy and dedupe rules.

## Optional OpenAI / Anthropic

Record model, account/organization, usage and pricing effective date. Account-specific ChatGPT credits, model availability or billing should never be hardcoded. AI classification of comments is advisory; hard policy remains deterministic.
