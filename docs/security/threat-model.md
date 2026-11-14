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

Inbox events intentionally have no foreign keys so signature-verified deliveries (including `installation.created` and out-of-order events) are durably recorded; an inbox row is verified, not authorized. Composite foreign keys prevent persisted cycle/finding/command/audit references from crossing installation, repository or reviewed SHA boundaries. They do not enforce per-user data access, active installation status or the latest remote SHA. Row-level security, immutable audit enforcement, webhook verification and dispatcher authorization are not implemented. Future ingress must verify the signature before inserting events; future workers must check installation/repository authorization before processing and recheck these gates before side effects.

Issue #11 adds a reusable authorization gate requiring both a deployment-configured stable installation/repository pair and a live ACTIVE installation with enabled database membership. It fails closed on missing membership and database errors. The opt-in token adapter checks that gate before exchange and again before returning a credential, requests one repository and one read operation, reloads the mounted RSA key per issuance and never follows redirects, caches tokens or propagates credential-bearing response/error bodies. GitHub remains the authority for remote App ownership, installation membership and suspension when minting a token. There is no automatic enrollment, OAuth, webhook ingestion or dispatcher yet. Tokens already handed to a future caller require a new authorization/expiry check before use; local policy changes cannot retroactively revoke remote tokens. See the [App runbook](../github/app-setup.md) for provisioning, rotation, revocation and known live-test limits.

## Security reviews

Any new external integration, public endpoint, identity flow, sandbox bypass, callback route or billable model call requires security impact review and an ADR if the trust model changes.
