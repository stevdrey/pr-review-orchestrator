# ADR 0003: Durable inbox/outbox and bounded idempotent dispatch

**Status:** Accepted (implementation pending)  
**Date:** 2026-11-13

## Context

GitHub can retry webhooks, events may arrive out of order, and external commands can time out after being accepted.

## Decision

Persist verified deliveries in PostgreSQL with unique delivery keys; acknowledge after commit. Process asynchronously from an inbox. Record outgoing commands in a transactional outbox with command-key deduplication, bounded retries, poison-message handling and correlation IDs. Reconcile remote state/commit SHA before side effects.

## Consequences

At-least-once processing with explicit idempotency, not a false promise of global exactly-once semantics. Postgres is adequate for initial throughput; no Kafka prerequisite.

## Alternatives

Direct webhook→GitHub call: event loss and duplicate actions. In-memory queue: state loss on crash. Kafka from day one: operational overhead without evidence of need.

## Verification

Crash-after-write, duplicate delivery, concurrent workers, lost API response and out-of-order commit tests.
