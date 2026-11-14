# GitHub webhook ingress — issue #12

`POST /webhooks/github` is an opt-in, metadata-only ingress. It authenticates raw bytes, validates explicit routes, authorizes repository membership and commits an inbox observation before acknowledging actionable events. It does not dispatch work, call GitHub/providers, interpret findings or update review state. The worker remains a separate increment.

## Enable and provision

Follow [App setup](app-setup.md) to provision the operator-approved installation and enabled repository membership in PostgreSQL. Both that live membership and the deployment allowlist are required, including for duplicates. Webhook enablement is independent of the outbound App token adapter; no App private key or user token is needed by ingress.

Mount a high-entropy secret file outside the repository, readable only by the service. Its **exact bytes**, including any newline, must match the secret configured in GitHub. Use 32–4096 bytes; avoid adding a newline when provisioning. Never put the secret in command-line arguments, logs or committed configuration. Example configuration with placeholder stable IDs:

```yaml
orchestrator:
  github:
    repositories:
      101: [1001]
    webhook:
      enabled: true
      secret-file: /run/secrets/github-webhook-current
      # Optional only during a controlled rotation window:
      # previous-secret-file: /run/secrets/github-webhook-previous
      max-payload-bytes: 1048576
```

Equivalent enablement variables are `ORCHESTRATOR_GITHUB_WEBHOOK_ENABLED=true` and `ORCHESTRATOR_GITHUB_WEBHOOK_SECRETFILE=/run/secrets/github-webhook-current`. Configure repository pairs using the YAML form above (or Spring command-line indexed properties as in the App runbook). Default: disabled, with no endpoint or secret requirement. Explicit enablement fails startup for missing/unreadable/short/oversized secrets or payload limits outside 1–26214400 bytes.

Configure the App webhook URL to the HTTPS ingress URL and select JSON. Terminate TLS at a trusted proxy; set connection/body-read timeouts, request/rate/concurrency limits and the same body cap there. The application caps both declared and streamed bodies, including chunked uploads. JSON media type must be `application/json`, with UTF-8 if a charset is specified; compressed bodies are rejected. Do not configure proxy/request tracing to log bodies or signature headers.

The local default body cap is 1 MiB, deliberately below GitHub's maximum; increase it within the configured bound if required by observed deliveries. PostgreSQL pool acquisition is bounded to 2 seconds, ordinary JDBC statements to 2 seconds, and inbox transactions to 3 seconds. These controls leave room for acknowledgements within GitHub's recommended 10-second response window under normal load; they are not a load-tested end-to-end latency guarantee.

## Routes and stored evidence

All routes require one UUID `X-GitHub-Delivery`, one `X-GitHub-Event` and one valid `X-Hub-Signature-256`. HMAC-SHA256 uses exact raw bytes and constant-time digest comparison **before JSON parsing**. Duplicate JSON fields and trailing JSON values are rejected. Repository events require positive integral installation/repository IDs, and the configured pair must have an ACTIVE installation and enabled membership in PostgreSQL.

| Event | Accepted actions | Additional routing evidence |
| --- | --- | --- |
| `pull_request` | opened, ready_for_review, synchronize, reopened, closed, edited, converted_to_draft | PR number and observed head SHA |
| `pull_request_review` | submitted, edited, dismissed | PR number/head SHA; valid review ID required |
| `pull_request_review_comment` | created, edited, deleted | PR number/head SHA; valid comment ID required |
| `pull_request_review_thread` | resolved, unresolved | PR number/head SHA; valid thread ID required |
| `issue_comment` | created, edited, deleted | PR-linked issue number and valid comment ID; head SHA remains unknown |
| `check_run` | created, completed, rerequested, requested_action | Check ID required; observed head SHA |
| `check_suite` | completed, requested, rerequested | Suite ID required; observed head SHA |
| `workflow_run` | requested, in_progress, completed | Run ID required; observed head SHA |
| `ping` | no action | Signed JSON with positive `hook_id`; handshake only, no inbox work or repository authorization |

Inbox rows preserve delivery ID, event type/action, installation/repository IDs, optional PR number/head SHA, payload SHA-256, server-generated correlation ID and verification time. They start `PENDING` with zero attempts. Raw bodies, titles, comments, signatures and secrets are neither persisted nor echoed. `payload_ref` remains null; detailed source IDs/text are not retained. A future worker must reconcile authoritative GitHub state rather than expect the original event body to be available. A resolved thread is just an observation, never proof of a fix. Out-of-order events retain their own observed SHA and do not overwrite review state.

Installation lifecycle events, ordinary issue comments, push events and unlisted actions are rejected; there is no automatic enrollment, membership sync or revocation handler. Operators must maintain local installation status/membership using the App runbook. Future workers must reauthorize and reconcile current remote state/SHA before processing and before any side effect.

## Responses and replay

| Status | Meaning |
| --- | --- |
| 202 | New authorized observation committed durably; `X-Correlation-ID` identifies it |
| 200 | Exact retry or replay of an already committed observation; original correlation ID, no new work |
| 204 | Authenticated ping; no actionable work accepted |
| 400 | Missing/malformed/ambiguous headers, malformed JSON or invalid routing metadata |
| 401 | Missing, malformed or invalid SHA-256 signature |
| 403 | Repository authorization denied, including unavailable authorization authority |
| 409 | Previously used delivery identity conflicts with stored event/scope/hash |
| 413 | Declared or streamed body exceeds the configured limit |
| 415 | Unsupported media type, charset or content encoding |
| 422 | Unsupported event/action or an issue comment unrelated to a PR |
| 503 | Secret, inbox write or transaction commit unavailable; no successful acceptance acknowledgement |

PostgreSQL unique delivery IDs serialize retries across instances/restarts. V2 adds a partial unique index on installation/repository/raw-payload hash for new ingress rows, so a captured body replayed with a different delivery ID cannot create another observation. The fingerprint deliberately excludes the event header because GitHub's signature covers the body, not headers. Conflicting event headers fail with 409 if they pass route validation. Exact byte-identical observations in the same scope are therefore coalesced even under different delivery IDs; replay aliases are not stored as separate rows. Existing V1 rows keep null routing columns and retain their original evidence.

HMAC has no signed timestamp and cannot establish event freshness. Replay suppression lasts while the inbox evidence is retained; deleting deduplication records permits old captured deliveries to be accepted again. Establish a retention/tombstone policy before production rollout. Altering signed bytes invalidates the signature; semantically equivalent but differently serialized bodies are distinct observations. This is at-least-once transport with deduplication, not exactly-once external effects.

## Rotate, disable and recover

1. Provision the old secret as the optional previous file, then atomically replace the current mounted file with the new secret and update GitHub's webhook secret. Coordinate deployment/configuration changes before accepting either key.
2. Both configured files are reloaded for every verified request. Keep overlap short, then remove previous-secret configuration and restart. Missing or invalid configured files fail closed at runtime with 503; retired secrets stop working once removed/replaced.
3. Disable ingress with `orchestrator.github.webhook.enabled=false` and restart to remove the endpoint. This does not delete accepted inbox evidence or revoke any GitHub token.
4. For non-2xx deliveries, inspect redacted error codes and database availability, fix the cause and explicitly redeliver through GitHub. Do not assume GitHub automatically retries failed deliveries. After an ambiguous network failure, redelivery is safe because successful commits deduplicate.

V2 is additive; back up before migration. Prefer disabling ingress or rolling back application code while retaining V2 columns/index and Flyway history. Do not edit an applied migration or delete accepted inbox rows to make a retry succeed. There is no public deployment/tunnel or production retention job in this change.

## Verification

- `./gradlew test --tests '*GitHubWebhook*'`: generated/local fixture secrets, HTTP against a real Spring Boot server and disposable PostgreSQL, raw UTF-8 tampering, malformed signatures/JSON/metadata, strict media types, declared/chunked size limits, missing/disabled/suspended/revoked/crossed membership, unavailable authority, missing rotated secret, explicit route coverage, exact/renamed replay, concurrent duplicate delivery, restart persistence, out-of-order SHAs and deferred commit failure with no successful acknowledgement.
- `./gradlew clean build`: also verifies existing credentials/authorization/schema behavior, V1→V2 upgrade preserving legacy evidence, migration checksums and second-startup no-op behavior.

Local evidence (2026-11-13): `./gradlew clean build --console=plain` passed with **58 tests, 0 failures/errors and 0 skipped**, including 16 webhook tests. `docker compose --env-file .env.example config --quiet` and `git diff --check` also passed.

Fixtures are synthetic and sanitized. Local tests do not prove live GitHub delivery, production latency, proxy/TLS setup, production retention, worker retries or review orchestration.

Protocol references: [GitHub signature validation](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries), [event payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads), [webhook best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks).
