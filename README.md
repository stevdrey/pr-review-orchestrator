# PR Review Orchestrator

> Event-driven, provider-neutral pull request review orchestration.

**Project status:** Initial application bootstrap with PostgreSQL and opt-in GitHub App authentication/installation authorization. Opt-in signed webhook ingress now persists authorized routing metadata; event dispatch and review orchestration remain planned.

PR Review Orchestrator is an independent open-source project to coordinate GitHub pull request reviews, CI events, remediation feedback and AI agents. The first target repositories are [Dokene](https://github.com/stevdrey/dokene) and [Monada Neuron](https://github.com/stevdrey/monada-neuron), but the architecture is repository-agnostic.

## Goals

- Receive and verify GitHub App webhooks for PR activity, reviews, commits, review threads, and CI.
- Request Codex reviews using an expressly authorized account, subject to verifying actual identity and usage attribution in a spike.
- Track findings from **inline review threads and general comments**; distinguish marking a thread resolved from verifying the underlying fix.
- Coordinate repeat reviews after new commits without duplicate requests or infinite loops.
- Apply deterministic policies first; optionally use OpenAI and other model providers for classification, deep review and approved auto-fix.
- Use configurable hard limits (initial proposal: six automatic iterations per PR), budget ledgers and stop conditions.
- Provide MCP tools and, when supported and enabled, an event bridge for ChatGPT Dots/Work.
- Notify authors about ready-for-human-review status; **never merge automatically by default**.

## Proposed technology

Java 27 · Spring Boot · Gradle · PostgreSQL · Flyway · GitHub App · GitHub REST/GraphQL · OpenTelemetry. The planned modular monolith separates domain logic from provider-specific adapters and optional agent workers.

## Planned lifecycle

1. Verified GitHub webhook enters durable inbox and is deduplicated.
2. Repository policy requests a review for a specific PR head SHA.
3. Review results are normalized into findings; general feedback counts even without a GitHub Resolve button.
4. On new push or explicit finding disposition, rules decide whether to re-review, wait, escalate or stop.
5. After a clean Codex baseline, optionally launch deep independent review (e.g., OpenAI/Claude).
6. Require up-to-date CI, no blocking findings, and a fresh clean review before notifying the author.

## Getting started

Install a JDK 27 distribution and Docker with Compose. From the repository root:

```sh
# Tests use a disposable PostgreSQL container; no .env or existing database is needed.
./gradlew clean build

# Local development database (non-production credentials only).
cp .env.example .env
docker compose up -d --wait
set -a
. ./.env
set +a
./gradlew bootRun
```

The environment export commands above are for POSIX shells. See [local database setup and rollback](docs/development/database.md) for configuration, migration behavior, persistence, backup and reset instructions. The application requires `DB_URL`, `DB_USER` and `DB_PASSWORD`; it does not automatically load `.env`. Flyway applies migrations on startup and validates their checksums. Docker must be reachable by the current user for integration tests; missing Docker fails the build rather than skipping database checks.

The Gradle wrapper downloads its pinned distribution on first use. Library dependencies are resolved from Maven Central and the Spring Boot Gradle plugin from the Gradle Plugin Portal; both are pinned by committed lockfiles (`gradle.lockfile`, `buildscript-gradle.lockfile`). On Windows use `gradlew.bat`. Once running, `curl http://localhost:8080/actuator/health` returns application and database health without connection details. This does not verify GitHub connectivity or review readiness. Stop the application with Ctrl+C; `docker compose down` stops the local database and preserves its named volume.

The bootstrap contains PostgreSQL/Flyway schema, database integration tests and a deny-by-default installation/repository authorization gate. The optional GitHub App client signs short-lived JWTs from a mounted key and requests single-repository, operation-scoped read tokens. See [GitHub App setup](docs/github/app-setup.md) for permission rationale, registration, allowlisting and credential rotation. GitHub credentials are unnecessary for the default-disabled configuration. An operator-approved [development App installation and live verification record](docs/github/issue-11-registration-evidence.md) is available. The opt-in [webhook receiver](docs/github/webhook-ingress.md) verifies raw-body signatures and commits authorized, deduplicated inbox metadata before acknowledgement. No event worker, user OAuth flow or review automation is supplied. Package dependency boundaries are not enforced automatically.

## Documentation

- [Roadmap](docs/ROADMAP.md)
- [Architecture](docs/architecture/overview.md)
- [Local database setup and rollback](docs/development/database.md)
- [GitHub App registration, authorization and rotation](docs/github/app-setup.md)
- [Signed webhook ingress, replay handling and rotation](docs/github/webhook-ingress.md)
- [Review lifecycle](docs/architecture/review-lifecycle.md)
- [Security and trust model](docs/security/threat-model.md)
- [Architecture decisions](docs/adr/README.md)
- [Codex attribution spike and test protocol](docs/spikes/issue-14-codex-attribution.md)
- [Contributing](CONTRIBUTING.md)
- [Agent instructions](AGENTS.md) and [skills](.agents/skills/README.md)
- [Project management and labels](docs/contributing/project-management.md)

## Cost and license

Provider usage may be charged separately from ChatGPT subscriptions or credits. Any budget settings are proposals until actual metrics and account entitlements are validated. Licensed under [AGPL-3.0-or-later](LICENSE), allowing commercial use, including SaaS, with AGPL conditions.

This project is not endorsed by GitHub, OpenAI or Anthropic.
