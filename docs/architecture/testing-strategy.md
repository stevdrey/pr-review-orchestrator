# Verification and testing strategy

## Current coverage

`./gradlew clean build` runs `DatabaseIntegrationTest` against disposable PostgreSQL 17.11 using Testcontainers. It covers Spring Boot migration from an empty database, Flyway checksum validation and no-op re-run, disabled clean, application/database health, duplicate delivery/command/cycle keys, installation/repository/SHA references, comment/thread distinctions, explicit fix evidence, retry bounds, installation-level records and transactional rollback of event/command/audit writes. Missing Docker is a failure, not a skipped test. Compose and tests use the same pinned image digest.

This verifies schema constraints and startup wiring, not a production inbox worker, webhook authorization, runtime budget enforcement or a complete review lifecycle. The remaining strategy below is planned.

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
