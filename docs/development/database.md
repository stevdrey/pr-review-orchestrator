# Local PostgreSQL and migrations

Implements the database foundation for [issue #10](https://github.com/stevdrey/pr-review-orchestrator/issues/10), following ADRs [0001](../adr/0001-independent-modular-monolith.md), [0003](../adr/0003-durable-event-inbox-outbox.md) and [0005](../adr/0005-finding-state-and-head-sha.md). This is schema and startup infrastructure; webhook validation, repositories, dispatch, budget gates and review state transitions are separate increments.

## Start locally

Requirements: JDK 27, Docker Engine accessible to your user, and Docker Compose v2 or later. POSIX shell commands from the repository root:

```sh
cp .env.example .env
docker compose --env-file .env.example config --quiet
docker compose up -d --wait
set -a
. ./.env
set +a
./gradlew bootRun
```

Compose loads `.env` automatically. Spring Boot does not: export the trusted local file as above, or set the variables in your IDE/OS. On PowerShell, set `$env:DB_URL`, `$env:DB_USER` and `$env:DB_PASSWORD` to the local values and run `./gradlew.bat bootRun` after starting Compose.

| Setting | Local example | Purpose |
| --- | --- | --- |
| `DB_NAME` | `pr_review_orchestrator` | Compose database name |
| `DB_USER` | `orchestrator_dev` | Compose bootstrap user and application login |
| `DB_PASSWORD` | `local_dev_only` | Public, disposable development password |
| `DB_PORT` | `54329` | Host port, bound only on `127.0.0.1` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:54329/pr_review_orchestrator` | Application JDBC URL |

Change `DB_PORT` in `.env` if occupied; sourcing the example derives the JDBC URL from it and `DB_NAME`. Without `.env`, Compose uses the same development defaults. The application has no credential fallback. Never copy these settings to a shared or production environment. `.env`, local data and backup directories are ignored by Git. Do not put real credentials or production data in fixtures or examples.

Compose and tests pin PostgreSQL 17.11 by image digest. Upgrade the digest in both `compose.yaml` and `DatabaseIntegrationTest` together and rerun migration tests. The `postgres-data` named volume persists across `docker compose down` and subsequent starts. PostgreSQL bootstrap variables initialize an empty volume only: changing the username, password or database name does not modify an existing database.

`curl http://localhost:8080/actuator/health` reports `UP` after successful database startup/migration. Connection details are hidden. A database outage makes aggregate health fail; this endpoint does not prove review readiness or GitHub access.

## Schema and guarantees

Flyway runs `src/main/resources/db/migration/V1__orchestration_foundation.sql` on application startup. An empty database receives seven tables plus `flyway_schema_history`:

| Table | Scope and purpose |
| --- | --- |
| `installations` | Stable installation/account IDs, lifecycle status and policy version |
| `installation_repositories` | Explicit repository membership; disabled by default |
| `review_cycles` | PR number, head SHA, provider/stage/iteration and state |
| `inbox_events` | Unique GitHub delivery key, payload digest/reference, correlation, verification timestamp and retry metadata |
| `findings` | Separate actionable findings with comment/review/thread provenance, reviewed SHA and disposition evidence |
| `outbox_commands` | Unique command key, cycle, expected SHA, provider and bounded retry metadata |
| `audit_entries` | Scoped actor/action/outcome, correlation and evidence reference |

UUID record/correlation IDs and deterministic command keys are supplied by future application code. Timestamps use `timestamptz`; application updates must maintain `updated_at`. SHA fields accept full lowercase 40- or 64-character hex commit IDs. Composite references bind findings and commands to a cycle's installation, repository and SHA. Audit records may be installation/repository-level, but cycle-specific records must include a matching repository and SHA. Installation-level events may omit repository/SHA. No cascading deletes remove evidence implicitly.

A repeated Flyway migrate validates checksums and applies zero migrations once V1 is recorded; the DDL is intentionally not wrapped in `IF NOT EXISTS`, which could hide drift. Keep applied migrations immutable and add `V2__...sql`, etc. Baseline-on-migrate is off: an unexpected existing nonempty schema is not silently adopted. Flyway clean is disabled. Failed PostgreSQL transactional migrations roll back; investigate and correct the cause before retrying, rather than deleting history or disabling validation.

Delivery keys deduplicate transport retries; command keys are a separate side-effect identity. Several findings can share one source comment/review and have independent dispositions. A resolved thread leaves a finding open. `VERIFIED_FIXED` requires an evidence commit, verification time, actor and reason; these fields record evidence but cannot establish its correctness. A general issue comment/review cannot acquire a thread-resolution flag. Application policy must still validate dispositions, freeze policy/budget context, authorize every action and compare the expected SHA with current remote state.

The retry ceiling defaults to five storage attempts and is not a review-iteration or spending policy. There is no dispatcher to claim rows, increment attempts, recover leases or perform side effects yet. Likewise, these foreign keys are integrity constraints, not row-level tenant security or an authorization check. Full source, webhook payloads, tokens and review bodies are not stored here; reference storage and retention must be designed before ingestion.

## Verify

```sh
docker compose --env-file .env.example config --quiet
./gradlew test --tests '*DatabaseIntegrationTest'
./gradlew clean build
```

Tests start an empty, disposable PostgreSQL container on a random port, supply its credentials directly to Spring, and exercise migration re-runs, constraints and rollback. They do not read `.env` or touch the Compose database. Docker unavailable means a test failure; there is no in-memory database substitute or automatic skip. CI runs these tests through `build`.

## Stop, backup and rollback

Stop the application first. `docker compose down` stops the database without deleting the volume. For a local logical backup while the database is running:

```sh
mkdir -p .data
docker compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > .data/local.backup.dump
```

Keep backups private and verify restore before relying on them. To restore into a **fresh empty database** (application stopped), using the same local configuration:

```sh
docker compose exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' < .data/local.backup.dump
```

Restore the matching application revision and validate Flyway history before accepting writes. There is no automatic down migration. In a data-bearing environment, stop writers, preserve a verified backup and use a reviewed forward migration or restore into a new database. Reverting Java code alone does not undo schema changes. Do not use `repair`, baseline, or edits to V1 to conceal checksum failures.

For a deliberately disposable local reset only, the following deletes **all local database data** in this Compose project's volume:

```sh
docker compose down --volumes
docker compose up -d --wait
```

The next application start reapplies V1. Never use this reset procedure against a shared or production database. Production backup/recovery, secret provisioning, TLS, role separation and data-retention automation remain out of scope.

## Issue #10 implementation evidence

Verified locally on 2026-11-13 with Java 27, Docker Engine 29.9.3, Compose 5.7.1 and PostgreSQL 17.11:

- `docker compose config --quiet` and `docker compose --env-file .env.example config --quiet`: passed.
- `./gradlew test --tests '*DatabaseIntegrationTest'`: passed. After final schema review, `./gradlew clean build` passed with 8 tests, 0 failures and 0 skipped.
- `docker compose -p pro-issue10-verification --env-file .env.example up -d --wait`: healthy. With the example exported, both `./gradlew bootRun --args='--server.port=18081'` and the final `java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar --server.port=18081` started, applied V1 and returned `UP` from `curl --fail --silent http://localhost:18081/actuator/health`. Verification processes were stopped intentionally afterward.
- Using that isolated Compose project, a synthetic installation survived `down` followed by `up -d --wait`. The documented `pg_dump --format=custom` / `pg_restore --exit-on-error` flow restored the record and successful V1 history into a fresh `restore_verification` database. The final schema was used; verification containers, volume and backup were removed afterward.
- `env -u DB_URL -u DB_USER -u DB_PASSWORD timeout 20s java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar --server.port=18082`: exited 1 during datasource initialization, as expected without configuration.
- `git diff --check`: passed. Examples and fixtures contain public development values and synthetic metadata only; `.env` and `.data/local.backup.dump` remain ignored.

These checks do not establish production deployment/recovery readiness or webhook/worker behavior. GitHub Actions results must be checked separately on the PR's exact head commit.
