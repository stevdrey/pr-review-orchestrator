# Verification and testing strategy (planned)

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

CI should add Java/Gradle build, tests, dependency and security scans only after a runnable Gradle project exists. Document exact checks and results; avoid false claims of current coverage.
