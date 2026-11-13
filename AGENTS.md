# Agent instructions

Provider-neutral entry point for AI coding agents (Codex, Claude Code and other compatible agents) working on **PR Review Orchestrator**.

## Required context

1. Inspect repository `main` and task's GitHub issue.
2. Read `README.md`, `docs/ROADMAP.md`, relevant architecture and accepted ADRs.
3. Read `.agents/README.md` and load only the necessary `.agents/skills/*/SKILL.md`.
4. Inspect existing implementation and tests **if they exist**; the bootstrap repository does not yet contain a runnable application.

## Precedence

Explicit user task > accepted ADRs > security invariants > this file > selected skills > local conventions. Record durable deviations in an ADR.

## Engineering contract

- Java 27 + Spring Boot (when implemented); hexagonal boundaries; repository-independent core.
- Trust GitHub events only after signature, installation and repository checks.
- Do not conflate general comments with resolvable review threads. Keep comments and findings distinct.
- Pin all review evidence and agent commands to a commit SHA and use idempotency keys.
- Deterministic gates control authorization, spending, loop limits, CI and human approval.
- No automatic merges or secret-bearing AI prompts; isolated execution for untrusted PR code.
- Never assume ChatGPT credit, Codex GitHub review, Dot or MCP Events entitlement; create capability probes and graceful fallbacks.
- Do not mark a finding fixed merely because a thread was resolved or a reviewer said “looks good”.

## Workflow

Scope → assess threat model → implement smallest coherent change → test deterministic paths and failures → update docs → summarize evidence and known limits. Do not fabricate green checks or shipped functionality.

## GitHub work

Prefer issue-driven tasks and clear labels. Implementing PR titles use `[Issue-N]`; do not open a PR when the user explicitly asks to commit to main. Avoid duplicate issue/PR/event creation and honor repository protections.

Do not place separate full copies of skills under `.claude/`, `.codex/` or `.cursor/`. Reuse `.agents/` as canonical guidance.
