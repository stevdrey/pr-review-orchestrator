# PR domain events and durable delivery

[Issue #25](https://github.com/stevdrey/pr-review-orchestrator/issues/25) adds a PostgreSQL outbox for committed PR review transitions. V19 attaches to the transition journal used by both the lifecycle and readiness invalidation. A transition, its event and the matching subscriber delivery rows commit together. Duplicate transition commands leave the original event in place. Existing journal history stays intact on upgrade. A new subscription receives matching transitions recorded after registration, with no historical replay.

The provider-neutral event and publisher port use a typed PR identity, full resulting head SHA, local state version, transition action, resulting state and optional reconciled CI status. The trace ID is the journal transition UUID. The outbox assigns a separate event UUID. The HTTPS adapter sends a JSON envelope with `eventId`, `name`, ISO 8601 `timestamp` and `data`. Its fixed `data` fields are `schemaVersion` (currently `1`), `installationId`, `repositoryId`, `pullRequestNumber`, `headSha`, `stateVersion`, `traceId`, `action`, `state` and nullable `ciStatus`. Source code, diffs, PR text, review bodies, paths, provider payloads and credentials are excluded.

The default name is `pr.review.transitioned`. Recorded clean stages emit `pr.review.completed`, blocking feedback emits `pr.feedback.detected`, a failed CI reconciliation emits `pr.ci.failed`, and entering human validation emits `pr.ready.for.validation`. An iteration cap emits `pr.review.limit.reached` and a no-progress stop emits `pr.human.approval.required`. A delivered event describes a historical transition for its SHA. Consumers should compare state versions when ordering events and independently authorize any requested action against current state.

## Register and enable a subscriber

Local publication is always available after migration, including with delivery disabled. A deployment operator must approve the receiver, verify consent and confirm the installation/repository allowlist and enabled membership. Configure the same callback and key mount on every active delivery replica. Keep the callback DNS and destination under the approved egress policy. The service uses TLS hostname verification and direct HTTPS, refuses redirects and accepts destinations only from trusted deployment configuration. It has no remotely supplied callback URLs. Enforce the approved DNS/IP destinations at deployment egress.

Use a fresh UUID per subscription. Mount an independently generated 32–64 byte raw key under `signing-key-directory/<subscription UUID>`, readable only by the service. Exchange it with the authorized receiver through the existing secret provisioning channel. Replace the mounted file atomically when rotating the key and coordinate the receiver's acceptance window.

```yaml
orchestrator:
  github:
    repositories:
      101: [202]
  domain-events:
    delivery-enabled: true
    signing-key-directory: /run/secrets/pr-domain-events
    callbacks:
      11111111-2222-4333-8444-555555555555: https://events.example.com/pr-transitions
```

Register using trusted database administration. Store a SHA-256 fingerprint of the exact ASCII callback URL as configured, including any explicit port or trailing slash. `NULL` in `pr_number` grants the entire authorized repository. The example restricts delivery to PR 7.

```sql
INSERT INTO pr_event_subscriptions
    (id, installation_id, repository_id, pr_number, callback_sha256)
SELECT '11111111-2222-4333-8444-555555555555'::uuid,
    i.installation_id, r.repository_id, 7,
    encode(sha256(convert_to('https://events.example.com/pr-transitions', 'UTF8')), 'hex')
FROM installations i JOIN installation_repositories r USING (installation_id)
WHERE i.installation_id = 101 AND r.repository_id = 202
    AND i.status = 'ACTIVE' AND r.enabled
RETURNING id;
```

Require the returned ID before starting a new subscription. The database binding is immutable. Register a new UUID to change the filter or destination. The delivery worker independently verifies the active membership and deployment allowlist before sending. When separating database roles, account for PostgreSQL row-lock privileges on the subscription and membership in addition to reads and queue updates. Keep subscription registration and revocation privileges with the trusted operator. The transition writer needs insert access to the outbox tables and read access to matching subscriptions.

## Verify and deduplicate deliveries

The adapter sends `Content-Type: application/json`, `webhook-id` (event UUID), `webhook-timestamp` (Unix seconds), `webhook-signature`, `X-MCP-Subscription-Id` and `Idempotency-Key`. The signature is `v1,` followed by base64 HMAC-SHA256 of `webhook-id + "." + webhook-timestamp + "." + rawBody`. The receiver should choose the key for its registered subscription, verify the signature over the original bytes in constant time and enforce a timestamp window. Both the event UUID and the `<subscription UUID>.<event UUID>` idempotency key stay stable across attempts. Derive the key from the authenticated subscription and signed event ID. Persist it atomically with receiver processing and return success on a verified duplicate. A new attempt signs with the current timestamp and mounted key. [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)

The worker commits an attempt before contacting the receiver. A 30-second PostgreSQL-clock lease and monotonically increasing attempt fence concurrent and restarted workers. A separate bounded transaction locks the delivery, the subscription and live membership through the POST and settlement. The worker's HTTPS budget is three seconds. A `2xx` records `DELIVERED`. Timeouts, network failures, missing callback/key configuration, temporary authorization outages, `408`, `429` and `5xx` retry after 5, 10, 20 and 40 seconds, up to five attempts. Other statuses, including `410` and `413`, are terminal. A crash after the receiver accepted a POST can repeat it with the same idempotency key. An expired final lease becomes `DEAD`. A receipt confirms transport acceptance.

## Revoke and inspect

```sql
UPDATE pr_event_subscriptions SET revoked_at = clock_timestamp()
WHERE id = '11111111-2222-4333-8444-555555555555' AND revoked_at IS NULL;
```

Revocation is permanent for that ID. It waits for an in-flight bounded send that already holds the subscription lock. Once committed, revocation prevents new sends and pending retries, including leases claimed earlier. New transitions exclude revoked subscribers. Queued attempts that reach the revocation check become `CANCELLED`. A receiver may still process bytes accepted during an earlier send. Disabling repository authorization also prevents further sends while denied. Remove an obsolete key and configured callback from delivery replicas after revocation.

Inspect `pr_event_deliveries` by `status`, `attempts`, `available_at` and `last_outcome`; correlate `event_id` with the trace UUID stored in `pr_domain_events.transition_id`. `DEAD` and `CANCELLED` are terminal. Ops retain control over data retention and backlog monitoring. Disabling the worker leaves the local event record and queued deliveries intact.

## Provider capabilities

The opt-in transport has no GitHub polling dependency. The synthetic Work web and Dot subscription lifecycle was validated separately in [Issue #24](../spikes/issue-24-mcp-events.md). This implementation supports operator-registered webhooks. Production Work or Dot registration and delivery, MCP event discovery and subscribe/unsubscribe RPCs, OAuth principals, callback challenges, provider-managed expiration, secret overlap, cursor replay and downstream task execution are outside this adapter. The local path runs with no provider credentials. Follow [Issue #26](https://github.com/stevdrey/pr-review-orchestrator/issues/26) for the adjacent MCP surface.

## Verification

`./gradlew --offline clean build` passed all 395 tests with no failures, errors or skips. PostgreSQL fixtures cover atomic rollback, V18 upgrade without replay, scoped fanout, concurrent claims and revocation, a failed commit after acknowledgement, and lease exhaustion. The HTTP adapter tests verify exact payload fields, signature bytes, callback binding, key rotation and retryable versus terminal responses. `git diff --check` passed.

The packaged application ran against PostgreSQL 17 and a loopback HTTPS receiver with an isolated TLS trust store and synthetic credentials. A `503` followed by `204` used identical event bytes and idempotency keys and a fresh HMAC timestamp. The receiver verified both signatures independently. A second event received `503`. Revocation cancelled its queued retry, and a third committed transition produced a local event with no delivery row. The receiver recorded three requests in total.
