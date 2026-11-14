# PR Review Orchestrator

> Event-driven, provider-neutral pull request review orchestration.

**Project status:** Initial application bootstrap. Review orchestration and GitHub integrations are planned, not yet implemented.

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

Install a JDK 27 distribution and run from the repository root:

```sh
./gradlew clean build
./gradlew bootRun
```

The Gradle wrapper downloads its pinned distribution on first use. Library dependencies are resolved from Maven Central and the Spring Boot Gradle plugin from the Gradle Plugin Portal; both are pinned by committed lockfiles (`gradle.lockfile`, `buildscript-gradle.lockfile`). On Windows use `gradlew.bat`. Once running, `curl http://localhost:8080/actuator/health` returns the application health status. This endpoint reports process health only; it does not verify GitHub connectivity or review readiness. Stop the application with Ctrl+C.

The bootstrap contains the application entry point, placeholder domain, application, ports and adapter packages, and a health integration test. Those packages contain no business functionality yet and their dependency boundaries are not enforced automatically. No webhook, persistence, credentials, or review automation is configured by this bootstrap.

## Documentation

- [Roadmap](docs/ROADMAP.md)
- [Architecture](docs/architecture/overview.md)
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
