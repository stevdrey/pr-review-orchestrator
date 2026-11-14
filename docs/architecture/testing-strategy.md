# Verification and testing strategy

## Current coverage

`./gradlew clean build` runs `DatabaseIntegrationTest` against disposable PostgreSQL 17.11 using Testcontainers. It covers Spring Boot migration from an empty database (V1/V2 history rows, no baseline), checksum validation and a no-op second application startup against the migrated database, disabled clean, application/database health, duplicate delivery/command/cycle keys, installation/repository/SHA references, finding idempotency keys, comment/thread distinctions, explicit fix evidence distinct from the reviewed SHA, retry bounds, processing leases and terminal timestamps, trigger-maintained `updated_at`, inbox recording of installation-level and not-yet-known installation/repository events and transactional rollback of event/command/audit writes. Missing Docker is a failure, not a skipped test. Tests read the pinned image digest from `compose.yaml`.

Issue #11 adds `RepositoryAuthorizationTest`, `GitHubAppConfigurationTest` and `GitHubCredentialsTest` for paired allowlists, live denial/unavailable authority, default-disabled startup, RS256 JWT signatures/lifetime, per-operation repository-scoped tokens, atomic key replacement, redacted failures, redirect/API/network errors, malformed/expired/over-scoped credentials, revocation during token exchange (with best-effort remote revocation of rejected tokens), response size caps and exchange deadlines. `DatabaseIntegrationTest` also exercises the JDBC authorization adapter across enabled/disabled and ACTIVE/SUSPENDED/REVOKED states. The focused command is `./gradlew test --tests '*GitHubAppConfiguration*' --tests '*GitHubCredentials*' --tests '*RepositoryAuthorization*'`; it uses local HTTP fixtures and generated keys, without GitHub credentials or Docker.

Issue #12 adds `GitHubWebhookSecurityTest` for raw-byte UTF-8 signatures, malformed digests, mounted-secret reload/rotation and fail-closed opt-in configuration. `GitHubWebhookIntegrationTest` exercises actual HTTP and PostgreSQL: route metadata, authentication before JSON parsing, malformed/ambiguous headers and JSON, strict media types, declared/chunked size caps, live authorization and revocation, unavailable authority/secret, exact/renamed/concurrent replay, restart persistence, out-of-order SHAs and deferred commit failure without a successful acknowledgement. `DatabaseIntegrationTest` also migrates a V1 schema with legacy inbox evidence to V2. The focused command is `./gradlew test --tests '*GitHubWebhook*'`; Docker is required. [Ingress limits and rollout](../github/webhook-ingress.md) distinguish local synthetic fixtures from live GitHub delivery.

These tests verify schema, startup wiring and local authorization/credential boundaries, not live App registration/installation, remote revocation, user OAuth, a production inbox worker, runtime budget enforcement or a complete review lifecycle. The remaining strategy below is planned.

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

Use a safe test repository/PR to demonstrate new PR → review → inline/general finding → push → re-review → deep review (optional) → CI check → ready notification. Assert that no merge occurred and that clean verdict matches the final SHA.

## Delivery controls

CI validates Compose configuration and runs the Gradle build with PostgreSQL integration tests on Java 27. Dependency and security scans and the remaining contract, unit and integration cases above remain planned. Document exact checks and results; avoid false claims of current coverage.
