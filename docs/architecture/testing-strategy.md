# Verification and testing strategy

## Current coverage

`./gradlew clean build` runs `DatabaseIntegrationTest` against disposable PostgreSQL 17.11 using Testcontainers. It covers Spring Boot migration from an empty database (V1–V9 history rows, no baseline), checksum validation and a no-op second application startup against the migrated database, disabled clean, application/database health, duplicate delivery/command/cycle keys, installation/repository/SHA references, finding idempotency keys, comment/thread distinctions, explicit fix evidence distinct from the reviewed SHA, retry bounds, processing leases and terminal timestamps, trigger-maintained `updated_at`, inbox recording of installation-level and not-yet-known installation/repository events and transactional rollback of event/command/audit writes. Missing Docker is a failure, not a skipped test. Tests read the pinned image digest from `compose.yaml`.

Issue #11 adds `RepositoryAuthorizationTest`, `GitHubAppConfigurationTest` and `GitHubCredentialsTest` for paired allowlists, live denial/unavailable authority, default-disabled startup, RS256 JWT signatures/lifetime, per-operation repository-scoped tokens, atomic key replacement, redacted failures, redirect/API/network errors, malformed/expired/over-scoped credentials, revocation during token exchange (with best-effort remote revocation of rejected tokens), response size caps and exchange deadlines. `DatabaseIntegrationTest` also exercises the JDBC authorization adapter across enabled/disabled and ACTIVE/SUSPENDED/REVOKED states. The focused command is `./gradlew test --tests '*GitHubAppConfiguration*' --tests '*GitHubCredentials*' --tests '*RepositoryAuthorization*'`; it uses local HTTP fixtures and generated keys, without GitHub credentials or Docker.

Issue #12 adds `GitHubWebhookSecurityTest` for raw-byte UTF-8 signatures, malformed digests, mounted-secret reload/rotation and fail-closed opt-in configuration. `GitHubWebhookIntegrationTest` exercises actual HTTP and PostgreSQL: route metadata, authentication before JSON parsing, malformed/ambiguous headers and JSON, strict media types, declared/chunked size caps, live authorization and revocation, unavailable authority/secret, exact/renamed/concurrent replay, restart persistence, out-of-order SHAs and deferred commit failure/timeout without a successful acknowledgement. Regression tests use string `thread.node_id` for both thread actions, reject missing/non-string/blank node IDs and prevent edited reviews/comments from being relabeled as PR events through the unsigned event header. `DatabaseIntegrationTest` migrates a V1 schema with legacy inbox evidence through V4. The focused command is `./gradlew test --tests '*GitHubWebhook*'`; Docker is required. [Ingress limits and rollout](../github/webhook-ingress.md) distinguish local synthetic fixtures from live GitHub delivery.

These tests verify schema, startup wiring and local authorization/credential boundaries, not live App registration/installation, remote revocation, user OAuth, live planning/command adapters, runtime budget enforcement or a live end-to-end review lifecycle. The remaining strategy below is planned.

## Contract tests

- GitHub webhook authentic/invalid signature, malformed payload, payload size, missing installation, revoked installation.
- Duplicate delivery, reordered review event, concurrent dispatcher races, process crash before/after external command.
- App vs user token identity: verify actor of the posted comment and separately test Codex entitlement/usage attribution.
- General comments without thread IDs; resolved thread still referring to broken code.
- New commit invalidates previous clean verdict; unknown CI status cannot be treated as passed.

## Unit/property tests

- Domain state transitions and impossible transitions.
- Attempt quotas (including retries), per-PR budget and global budget reservations, overflow and concurrent spending.
- No-progress stop conditions and human approval override scope.
- Idempotency key determinism, correlation and replay safety.

## Integration tests

PostgreSQL migrations, inbox/outbox transactions, token rotation, GitHub API rate limit/timeout simulation, provider errors. Use fixtures that contain no actual secrets, private source or customer data.

## End-to-end acceptance

[Issue #23](https://github.com/stevdrey/pr-review-orchestrator/issues/23) adds `FeedbackLoopSandboxIntegrationTest` to the regular Gradle suite. It uses disposable Git commits, the Spring HTTP webhook endpoint, PostgreSQL/Flyway, and a loopback server for the GitHub API. The test drives the internal reconciliation services from claimed inbox rows and exercises the Codex request and author notification HTTP adapters with generated or synthetic test credentials.

| Scenario | Evidence asserted by the sandbox test |
| --- | --- |
| Authenticated opening and replay | Invalid signatures and foreign repositories create no work. Same-ID and renamed replays create one inbox row. Repeating the request through a new adapter instance leaves one GitHub comment for the opening head. |
| Findings, fix and re-review | A complete fixture snapshot creates an inline finding and a general review-body finding. Git confirms a changed committed tree. The general finding requires an audited disposition for the fix head. Failed or stale CI blocks re-review until required checks pass for that head. |
| Clean evidence and notice | Retained findings block a clean completion until the sandbox verifier records the inline fix and a human fixture dismisses the general finding at the fix head. Requests, cycles, snapshots and reviewed source SHAs are checked for all rounds. The final head has correlated baseline and deep evidence. Passing current-head CI enables one author notice despite transition replay, pause/resume and a bot-comment event. |
| Policy stops and uncertain receipt | A denied test budget creates zero reservations and zero comments. Repeating comparable findings pauses the PR and prevents another request. A separate scenario reaches the configured iteration limit while budget remains available. A comment accepted by the fixture with an ambiguous response keeps a durable send permit and is never posted again on replay. |
| Allowed effects and data | The HTTP fixture accepts only PR preflight reads, scoped authentication, token revocation and PR conversation comments. An approval, merge or other unexpected route fails the test. The two Codex triggers contain the fixed command and the author notice excludes test credentials and raw review/PR text. |

Run from a checkout with Git, Java 27, Docker and a populated Gradle cache.

```sh
./gradlew --offline test --tests '*.FeedbackLoopSandboxIntegrationTest'
./gradlew --offline clean build
git diff --check
```

On 2026-11-19, the focused run passed all 4 scenarios. The clean build passed all 312 tests with zero failures, errors or skips. Whitespace checks passed.

The sandbox supplies structured completions, complete GitHub read snapshots, CI outcomes, a per-key budget fixture and the deep-review receipt. The test checks the two committed file revisions and records the proof through [the internal sandbox verifier](../development/fix-correlation.md#verify-an-inline-fix-in-the-sandbox). The test ages the recorded head observation to exercise the persisted quiet period without a 30-second wait. The authenticated provider completion, financial accounting and delivery of the author mention remain live acceptance work.

### Functional validation with Codex disabled

Run the signed-ingress-to-notice scenario against the loopback GitHub fixture and disposable PostgreSQL. It exercises opening and replay, baseline and deep review completion, findings, an audited general decision, the inline fix, current-head CI and a single author notice. The suite also exercises stale evidence, authorization failures, no progress, iteration exhaustion and an ambiguous receipt. The fixture accounts for its own review requests and notices. Real GitHub review events, review and CI reconciliation, one author acknowledgment and one inline fix have separate sandbox evidence in [draft PR #67](https://github.com/stevdrey/pr-review-orchestrator/pull/67).

### Cost evidence deferred

Per-review cost and billing attribution cannot currently be evaluated. The available activity data aggregates reviews and findings and does not provide an authoritative link from an individual review to charged units, the billed account or workspace, or the funding mechanism. This functional validation uses synthetic budget decisions. It tests the stop behavior while leaving provider cost and usage reconciliation unevaluated. Live dispatch remains disabled under the accepted authorization and spending gates. [Accounting investigation](../spikes/issue-42-review-accounting.md) · [Budget reservations and hard limits](https://github.com/stevdrey/pr-review-orchestrator/issues/28)

### Live sandbox acceptance

Use an operator-approved disposable GitHub repository and PR with synthetic source, known required checks, and an approved App installation. The live run depends on approved event and command wiring, a complete GitHub review/CI reader, verified OAuth identity and an authenticated provider completion contract. Keep the Codex request path disabled until the prerequisites in [ADR 0002](../adr/0002-github-app-webhook-trust.md) and [ADR 0004](../adr/0004-deterministic-policy-and-budget-gates.md) are met. The [issue #42 accounting decision](../spikes/issue-42-review-accounting.md) records the unresolved per-review attribution. [Issue #28](https://github.com/stevdrey/pr-review-orchestrator/issues/28) tracks budget reservations and usage reconciliation. Use only human-authorized finding dispositions and independently verified inline fix evidence.

Once those prerequisites are available, record the sandbox PR URL, delivery IDs and correlation IDs, original and fixed commit SHAs, review request and completion IDs with the provider-confirmed SHAs, finding and disposition IDs, required-check run URLs, and the final author comment URL. Replay a delivery and a request at the same head and compare effect counts. Demonstrate CI, budget, iteration and no-progress stops within the sandbox's approved cap. Verify that the PR is still open and that no orchestrator approval or merge appears in the timeline. Keep credentials and raw payloads out of the evidence record.

## Delivery controls

CI validates Compose configuration and runs the Gradle build with PostgreSQL integration tests on Java 27. Dependency and security scans and the remaining contract, unit and integration cases above remain planned. Document exact checks and results; avoid false claims of current coverage.

## Durable dispatch infrastructure (issue #13)

`DurableDispatchIntegrationTest` exercises real PostgreSQL claims/transactions with two installation/repository scopes and synthetic planner/gateway adapters. It covers concurrent logical command deduplication, deferred completion-commit rollback, token fencing, restart recovery, final-attempt crash dead letters, lost API response and remote-accept/local-commit crashes, applied-effect reconciliation after the PR head advances, unsettled-effect retries across head changes through later settlement or REMOTE_UNCERTAIN dead-lettering, reconciliation-only unknown outcomes, stale/out-of-order SHAs, revocation, transient authorization-authority outages with recovery, poison handling, bounded backoff, sanitized correlation logs and locked-effect concurrency. `JdbcWorkQueueLoggingTest` checks Spring DEBUG and rollback-failure ERROR logs for leaked causes/suppressed exceptions; `DatabaseIntegrationTest` also preserves V3 journal evidence through V4 and rechecks the stable-code constraint. `DurableDispatchConfigurationTest` verifies default-disabled startup, required adapter wiring and bounded identities/limits. Run `./gradlew test --tests '*DurableDispatch*' --tests '*JdbcWorkQueueLoggingTest' --tests '*RepositoryAuthorizationTest' --tests '*DatabaseIntegrationTest'`; Docker is required.

These fixtures validate the durable protocol rather than live GitHub side effects or actual process kills. The [adapter contracts](../development/durable-dispatch.md) require provider idempotency, fresh SHA and action/approval/budget gates before a production gateway can execute. No live planner/gateway is enabled by default.

## Persistent review state (issue #15)

`PullRequestReviewTest` checks the full action/state graph, remediation cycle, all pause/resume targets, SHA invalidation from every state, a force-push back to an earlier SHA, stale and repeated actions, eligibility/policy/CI guards and concurrent immutable updates. `ReviewStateIntegrationTest` uses PostgreSQL for restart persistence, scoped transition keys, identical and distinct concurrent commands, authorization denial/unavailability, deferred commit rollback and retry, database evidence constraints and V4→V5 migration preserving legacy cycles without inferring a current verdict. `DatabaseIntegrationTest` verifies V5 on startup and migration reruns.

Run `./gradlew test --tests '*PullRequestReviewTest' --tests '*ReviewStateIntegrationTest'`; Docker is required for persistence tests. The [internal caller contract](../development/review-state-machine.md) requires authoritative correlated observations and deterministic policy decisions. These fixtures do not verify live review providers, budgets, notifications or deployment.

## Inline review evidence (issue #16)

`GitHubReviewNormalizerTest` checks source metadata, body revisions without retained prose, explicit severity, reply/root distinction, resolution/outdated signals, scope and SHA mismatch, malformed/partial/paginated responses and duplicate IDs. `ReviewEvidenceIntegrationTest` checks atomic PostgreSQL ingestion, out-of-order edits, stable identities across rounds, distinct round/source SHAs, concurrent idempotent retries, conflicting keys, recreated-store persistence, authorization revocation, scope foreign keys, deferred commit rollback, empty-thread evidence, conservative absence handling and V5→V6 migration preserving legacy findings.

Run `./gradlew test --offline --tests '*GitHubReviewNormalizerTest' --tests '*ReviewEvidenceIntegrationTest' --tests '*DatabaseIntegrationTest'`; Docker is required. Fixtures are synthetic GraphQL-shaped responses, not live API captures. The [internal caller contract and limitations](../development/review-evidence.md) remain essential; no live webhook-to-review workflow, semantic classification or fix verifier is claimed.

## Codex review requests (issue #18)

`ReviewRequestIntegrationTest` checks PostgreSQL concurrency, event-independent keys,
restart persistence, one-shot send permits, deferred commit failure after remote
acceptance, ambiguous response handling, request ceilings, revoked/foreign scopes,
comment/bot loop filtering and unsupported capability. `GitHubCodexCommentsTest`
checks fixed user-comment requests with local HTTP fixtures, actor/expiry/scope,
readiness and SHA checks, final authorization/policy denial, redirect refusal,
body caps, body deadlines and redacted failures. `CodexReviewConfigurationTest`
checks default-disabled behavior and missing approved integrations.

Run `./gradlew test --tests '*ReviewRequestIntegrationTest' --tests '*GitHubCodexCommentsTest' --tests '*CodexReviewConfigurationTest'`;
Docker is required. These tests do not prove live OAuth provenance, actual Codex
trigger acceptance/reviewed SHA, accounting/budgets or end-to-end dispatch. See
[activation, recovery and rollback limits](../development/codex-review-requests.md).

Issue #19 adds deterministic outcome classification, durable request/cycle correlation, retained-finding checks and atomic clean provider/stage/SHA evidence. See [review outcomes and exact verification commands](../development/review-outcomes.md). Live completion authentication and remote reconciliation remain unverified.
