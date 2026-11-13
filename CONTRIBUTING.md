# Contributing

Welcome! The project is currently in the design/implementation phase; review the [roadmap](docs/ROADMAP.md) and issues first.

## Before changing anything

1. Read `README.md`, `AGENTS.md`, [architecture](docs/architecture/overview.md) and the accepted [ADRs](docs/adr/README.md).
2. Search for an existing issue; for a new capability, propose scope, security implications, acceptance criteria and planned tests.
3. Make focused, testable changes. Record design changes in ADRs; never change an accepted architectural decision silently.
4. Include tests for both happy paths and failure/replay paths (especially auth, webhook signatures, idempotency, budgets and review states).
5. Document actual verification evidence; do not claim tests ran if they did not.

## Proposed development convention

Java 27, Spring Boot, Gradle wrapper, PostgreSQL and Flyway are the intended stack. Do not claim commands exist before implementation adds them. Prefer modular boundaries, constructor injection, immutability, structured logs, and explicit policy types. Avoid provider-specific assumptions in the domain.

## Pull requests

Title: `[Issue-123] Short imperative outcome`. Body: **What**, **Why**, **Changes**, **How to test / verify**, **Risk & rollout**, **Notes for reviewers**. Link the primary issue with `Closes #123` only when the change completes it. No fabricated CI evidence. Human approval remains required for merges according to the repository's governance.

## Security

Do not commit secrets, private access tokens, actual webhook payloads, production PR diffs, sensitive source excerpts, or model outputs containing credentials. Open a privately disclosed security report; see [SECURITY.md](SECURITY.md).

## License

Contributions are provided under AGPL-3.0-or-later. Confirm you have the right to contribute the material.
