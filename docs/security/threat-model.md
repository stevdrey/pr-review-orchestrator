# Threat model — initial baseline

**Security-first and fail-closed**, with a publicly exposed webhook ingress and optional AI/agent adapters.

## Assets and trust boundaries

- GitHub App private key, user authorization tokens, webhook secrets.
- Repository contents/PR diffs and reviews, CI logs, installation/repository identifiers.
- Authorization decisions, audit records, external API keys, spending budget.
- Boundary A: Internet → signed webhook ingress.
- Boundary B: persisted event queue → orchestrator commands.
- Boundary C: external GitHub/provider responses and PR-authored text → policy engine.
- Boundary D: AI review outputs → public comments or isolated fix worker.
- Boundary E: outbox → MCP/Dot callbacks, email and notifications.

## Threats → mandatory controls

| Threat | Control |
| --- | --- |
| Forged/replayed webhooks | Raw-body HMAC-SHA256 verification, unique delivery IDs, short bounded payloads |
| Cross-repository or installation confused deputy | Stable repository ID + installation authorization every request |
| Token or secret leakage | Encryption at rest/secret manager, least privilege, redacted logs, rotation |
| Prompt injection from PR comments or CI output | Treat as untrusted data; agents cannot override deterministic policies |
| Side-effect replay / self-triggered review loop | Command idempotency, outbox, originating bot filter, SHA check, attempt cap |
| Stale clean verdict | Bind result to reviewed SHA and revalidate against current head |
| Excessive spend or infinite cycles | Per-PR, per-installation and global hard quotas; fail closed on missing usage |
| Malicious PR execution | Isolated ephemeral workers, network/credential restrictions, no host secrets |
| Exfiltration via provider | Explicit data minimization, approved providers, retention control, audit |
| Unauthorized merge / approvals | No auto-merge, explicit human authority and protected branch rules |

## Required abuse-case tests

Malformed/signature mismatch; delivery replay; concurrent identical events; comments requesting secret export; repo installation revoked mid-cycle; AI response requesting unauthorized GitHub action; untrusted PR CI that attempts to read host secrets; budget exhausted; stale SHA after force push; generalized comment with no reply/resolve button.

## Data retention

Prefer event metadata and references over storing full PR source. Document retention, privacy and deletion policies before launch. Never include access tokens or raw private diffs in public issue comments or log snapshots.

The initial PostgreSQL schema stores metadata, hashes and optional references rather than raw event/review bodies. A reference must not contain a credential or signed access URL. Local Compose binds to `127.0.0.1` with disposable, explicitly non-production credentials; the image's bootstrap user is a database superuser for local development only. Real deployments require separately provisioned credentials, least-privilege application/migration roles, encrypted transport/storage, backups and retention policy. No production database deployment is supplied here.

Inbox events intentionally have no foreign keys so signature-verified deliveries (including `installation.created` and out-of-order events) are durably recorded; an inbox row is verified, not authorized. Composite foreign keys prevent persisted cycle/finding/command/audit references from crossing installation, repository or reviewed SHA boundaries. They do not enforce per-user data access, active installation status or the latest remote SHA. Issue #12 implements opt-in raw-body webhook verification and paired installation/repository authorization before metadata acceptance. Row-level security and immutable audit enforcement remain unimplemented. Workers must check installation/repository authorization before processing and recheck these gates before side effects.

Issue #11 adds a reusable authorization gate requiring both a deployment-configured stable installation/repository pair and a live ACTIVE installation with enabled database membership. It fails closed on missing membership and database errors. The opt-in token adapter checks that gate before exchange and again before returning a credential, requests one repository and one read operation, reloads the mounted RSA key per issuance and never follows redirects, caches tokens or propagates credential-bearing response/error bodies. GitHub remains the authority for remote App ownership, installation membership and suspension when minting a token. There is no automatic enrollment or OAuth. Default-disabled dispatch infrastructure is supplied by issue #13. Tokens already handed to a future caller require a new authorization/expiry check before use; local policy changes cannot retroactively revoke remote tokens. See the [App runbook](../github/app-setup.md) for provisioning, rotation, revocation and known live-test limits.

Issue #12 adds a bounded Internet→inbox boundary: HMAC over exact bytes before strict JSON parsing, mounted current/previous secrets reloaded per request, explicit event/action routes and live paired authorization on every delivery (including retries). PostgreSQL commits metadata before returning 202; failures return redacted error codes. Unique delivery IDs and scoped raw-body fingerprints suppress concurrent, restarted and relabeled replays. Headers and freshness are not authenticated by the HMAC; retained fingerprints prevent a captured body from creating more work under a new delivery ID. No signed timestamp or production replay-retention policy exists. Signed ping is a handshake with no repository authority or queued work; installation lifecycle events are not accepted. Raw bodies/source text are discarded. No provider call, review-state transition, finding resolution or arbitrary code execution occurs in ingress. See [routes, response semantics, rotation and rollout limits](../github/webhook-ingress.md). This implements the existing ADR 0002/0003 boundaries without changing the trust model.

## Security reviews

Any new external integration, public endpoint, identity flow, sandbox bypass, callback route or billable model call requires security impact review and an ADR if the trust model changes.

Issue #13 implements the persisted queue-to-command boundary with fresh per-claim tokens, PostgreSQL-clock leases, concurrent claims, bounded crash recovery and atomic inbox/outbox/journal commits. Authorization is checked before planning and inside completion/dispatch transactions. Outbox execution holds row and live authorization locks through bounded reconciliation and completion. Stale SHA cancels the command; unknown remote settlement permits reconciliation retries only. The gateway must enforce fresh SHA and remote idempotency at the actual side-effect boundary, plus action/approval/budget policy; absent approved planner/gateway beans, opt-in activation fails startup. No live side-effect adapter or new public endpoint is supplied. Error journals/logs use stable codes and local correlation IDs without exception bodies. These controls implement ADR 0003; they do not provide global exactly-once external effects, production retention, policy budgets or immutable audit. See [dispatch contracts and recovery limits](../development/durable-dispatch.md).

PR #47 review fixes distinguish confirmed authorization denial from authority unavailability: both fail closed, while only unavailability schedules bounded retries. Before Spring transaction rollback, worker runtime exceptions are copied to stable-code failures without raw causes/suppressed exceptions; this also protects Spring rollback diagnostics when the database connection fails. Regression tests cover both queues, the retry ceiling, actual Spring logging, and migration of existing V3 journals to V4.

Issue #15 adds an internal current-PR aggregate and transactional transition journal. Authorized reads and writes are bounded transactions; membership share locks cover version/SHA checks and atomic commit. Scoped UUIDs and expected versions reject replays and stale concurrent observations. Every observed head change clears baseline/deep clean evidence, including while paused and when returning to an earlier SHA. No external endpoint, provider command or budget override is added. Gate values and clean actions are trusted internal decisions, not raw webhook/model inputs; future callers must correlate authoritative head/review/CI/findings and policy evidence before invoking the use case. The [caller contract](../development/review-state-machine.md) preserves remote side-effect checks and explicitly excludes inferred approvals, row-level tenant security and immutable audit guarantees.

Issue #16 adds an internal authorized review-evidence transaction, not a new Internet endpoint. Source text is hashed and discarded; source IDs, author login, path/line metadata, commit SHAs and resolution signals are retained as untrusted observations. Composite keys bind observations and finding occurrences to the same installation/repository/PR/cycle. Callers must fetch complete authoritative API state under an authorized scoped credential; arbitrary caller timestamps, body hashes or resolved flags cannot establish a fresh clean verdict or verify a fix. See [caller, retention and rollout limits](../development/review-evidence.md).

Issue #17 extends this internal boundary to general comments and review bodies. Non-empty unstructured feedback is escalated and blocks advancement; prose/approval state cannot authorize disposition. The trusted action adapter must authenticate an author/maintainer and bind the actor before invoking the internal disposition bean; no public action endpoint exists. Live paired authorization, source revision, current aggregate head, scoped foreign keys, PR transaction serialization and UUID deduplication protect the audit/projection transaction. Proposed fix commits remain unverified and blocking. Pending feedback invalidates prior clean readiness atomically, including paused states. Raw text is discarded; bounded human reasons and proposed commit SHAs are retained as sensitive audit metadata. Privileged database audit immutability, production retention and user-facing authentication remain unimplemented. See the [caller and rollback contract](../development/general-feedback.md).
