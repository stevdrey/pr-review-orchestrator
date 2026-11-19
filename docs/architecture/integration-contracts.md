# Integration contracts and capability verification

This document describes planned endpoints and adapter contracts. The [GitHub App authentication and installation authorization adapter](../github/app-setup.md) is implemented as an internal opt-in Java component; the [webhook receiver](../github/webhook-ingress.md) is a separate opt-in HTTP adapter.

## GitHub ingestion

Events to investigate: `pull_request` (opened, ready_for_review, synchronize, reopened, closed), `pull_request_review`, `pull_request_review_comment`, `pull_request_review_thread`, `issue_comment`, `check_run`, `check_suite`, `workflow_run`. GitHub delivery semantics and payload availability depend on app event subscription and permissions; verify each combination via captured **sanitized** fixtures.

Implemented ingress: `POST /webhooks/github`. Verify bounded raw HMAC-SHA256 bytes, delivery ID, installation, repository and explicitly supported event/action before queuing metadata. Return 202 after commit, 200 for a durable duplicate, or 204 for a signed ping that queues no work. See the [route and response contract](../github/webhook-ingress.md) for unsupported lifecycle events, replay fingerprints, secret rotation and metadata-only limitations. Detailed payloads are not retained; an approved dispatch planner must fetch authoritative GitHub state. Issue #16 supplies [internal review/thread snapshot normalization and durable ingestion](../development/review-evidence.md); issue #17 extends it with [general-feedback capture, internal dispositions and persisted gates](../development/general-feedback.md). Live fetching, authenticated author actions and webhook reconciliation remain caller responsibilities and are not wired by default.

Outbound GitHub: limited to app-authorized operations; use user-to-server authorization where necessary for account attribution and verify Codex subscription ownership in an integration test. GitHub comment author identity and Codex billable identity may differ.

The [Codex attribution spike](../spikes/issue-14-codex-attribution.md) records observed trigger behavior, attribution limits, the controlled test protocol and a fail-closed fallback. User-token and automatic triggers succeeded in the tested configuration. [Issue #42](https://github.com/stevdrey/pr-review-orchestrator/issues/42) concludes the accounting investigation with an owner-accepted limitation: per-review financial attribution remains unverified and is still a prerequisite for enabling a request path.

The [issue #42 accounting investigation](../spikes/issue-42-review-accounting.md)
assesses available provider sources and defines an owner-assisted counter experiment.
An aggregate increase is activity correlation, not an individual debit or billing
identity. Keep both request paths disabled until accounting and enforceable budget
controls are verified; missing usage must not be recorded as zero.

## ReviewProvider port

Issue #18 implements the narrow internal `ReviewProvider.request(ReviewRequest)` request boundary and [disabled-by-default Codex adapter](../development/codex-review-requests.md), with durable one-shot request reservations. Its GitHub comment receipt is distinct from provider acceptance/completion and cannot establish reviewed SHA. Approved OAuth and accounting/budget integrations are required; no default live dispatcher wiring exists.

Other proposed operations: `requestReview(ReviewRequest)`, `getReviewOutcome(ProviderReference)`, `summarizeFindings(ReviewContext)`. Providers can be async; commands must carry idempotency key, review stage, budget reservation and expected head SHA. No generic provider contract should require a human login or a fixed model name.

Issue #19 supplies the [internal outcome classifier and clean-recording boundary](../development/review-outcomes.md). PostgreSQL validates request/cycle/provider/stage/SHA correlation and retained findings against persisted evidence, with atomic clean provenance. An approved caller must establish authenticated completion/reviewer identity and ingest complete current remote evidence. Structured completion is distinct from a comment receipt or GitHub approval; unsupported/unstructured results cannot advance. Live Codex completion retrieval and remote input gathering are not supplied.

## AgentEventPublisher / MCP

An optional adapter may expose read-only PR status and controlled actions via MCP tools, and publish subscribed domain events to compatible platforms (including Dots/Work **only after a real capability test**). The application is event-driven regardless of MCP client availability.

Possible event kinds: `pr.review.completed`, `pr.feedback.detected`, `pr.feedback.resolved`, `pr.ci.failed`, `pr.ready.for.validation`, `pr.review.limit.reached`, `pr.human.approval.required`. Include event ID, repo/PR identifiers, SHA, trace ID and only redacted metadata. Apply replay/idempotency and opt-in subscription policies; do not send full source/diffs by default.

## Author notifications

The [author notification outbox and GitHub adapter](../development/author-notifications.md) deliver metadata-only PR conversation comments using a single-repository App token. Email requires a verified address and explicit opt-in.

## Optional OpenAI / Anthropic

Record model, account/organization, usage and pricing effective date. Account-specific ChatGPT credits, model availability or billing should never be hardcoded. AI classification of comments is advisory; hard policy remains deterministic.

## Durable worker adapter contracts

Issue #13 supplies provider-neutral `EventPlanner`, `CommandGateway` and `WorkQueue` ports. Approved planners return bounded metadata-only intents; command gateways explicitly support registered actions and reconcile authoritative PR head/remote command-key evidence before writes. Unknown outcomes remain reconciliation-only. No live command adapters are supplied; default-disabled activation fails without approved beans. See [transaction, deadline, policy and idempotency requirements](../development/durable-dispatch.md).
