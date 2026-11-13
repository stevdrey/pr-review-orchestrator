# ADR 0001: Independent modular monolith and ports/adapters

**Status:** Accepted (architecture target; implementation pending)  
**Date:** 2026-11-13

## Context

The orchestrator initially serves Dokene and Monada Neuron but must remain independently deployable and usable by other repositories and agents.

## Decision

Use Java 27, Spring Boot, PostgreSQL and Flyway in a modular monolith. Domain and application rules depend on ports, not GitHub, Codex, OpenAI, Claude, email or MCP SDKs. Integrations are adapters. Async processing stays inside the service initially, with a durable DB queue.

## Consequences

Simpler startup, tests and operations than distributed services. Clear seams for future providers and horizontal workers. Java 27/toolchain availability must be verified in CI before implementation.

## Alternatives

Direct scripts or GitHub Actions-only: faster initially but weaker centralized state/cross-repo policy. Early microservices/Kafka: unnecessary operations overhead at this scale.

## Verification

Domain-rule unit tests without provider SDKs; integration smoke test with two independent repository policies.
