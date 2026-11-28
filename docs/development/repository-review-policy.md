# Repository review policy

[Issue #27](https://github.com/stevdrey/pr-review-orchestrator/issues/27) adds a policy for each stable installation and repository ID pair. Version `0` describes the implicit default. It permits zero automatic iterations, has empty stage and provider allowlists, and requires passing CI. Publish an explicit version before enabling review actions for a repository.

## Operate the policy

Use a named PostgreSQL login for each operator identity. A database administrator provisions an active, enabled installation/repository pair and inserts a grant for the operator's login and attributed actor ID. The operator login needs database connection and schema usage, `SELECT` on the four tables below and `INSERT` on `repository_review_policy_versions`. PostgreSQL also requires `UPDATE` privilege on one column per membership table for the authorization read locks. Use timestamp columns for that grant. Keep grant writes, schema ownership and policy-table `UPDATE`, `DELETE` and `TRUNCATE` with the administrator. The runtime service login needs read access to the version table.

Example for schema `public`, database role `pro_review_alice`, installation `101`, repository `1001` and actor `42`.

```sql
GRANT USAGE ON SCHEMA public TO pro_review_alice;
GRANT SELECT ON public.installations, public.installation_repositories,
    public.repository_review_policy_operators, public.repository_review_policy_versions TO pro_review_alice;
GRANT UPDATE (updated_at) ON public.installations TO pro_review_alice;
GRANT UPDATE (created_at) ON public.installation_repositories TO pro_review_alice;
GRANT INSERT ON public.repository_review_policy_versions TO pro_review_alice;

INSERT INTO public.repository_review_policy_operators (installation_id, repository_id, database_role, actor_id)
VALUES (101, 1001, 'pro_review_alice', 42);
```

Run the application migration before using the operator command. Set `DB_URL`, `DB_USER` and `DB_PASSWORD` for the operator login through the deployment's usual secret configuration. Use the deployment's paired GitHub allowlist and set the target and actor in trusted Spring configuration. For example, with the bootstrap pair above in a local deployment.

```sh
export SPRING_APPLICATION_JSON='{"orchestrator":{"github":{"repositories":{"101":[1001]}},"repository-policy":{"operator":{"installation-id":101,"repository-id":1001,"actor-id":42}}}}'
java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar repository-policy show
java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar repository-policy configure 0 3 true BASELINE codex America/Costa_Rica 22:00 06:00
java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar repository-policy pause 1
java -jar build/libs/pr-review-orchestrator-0.1.0-SNAPSHOT.jar repository-policy resume 2
```

- `configure EXPECTED_VERSION CAP REQUIRE_PASSING_CI STAGES PROVIDERS [ZONE START END]` publishes validated settings and preserves the repository hold. The cap is 0–6. A lower deployment cap still applies where configured. Stages are `BASELINE` and `DEEP`. Use comma-separated allowlists, or `-` for an empty list. Provider IDs use lowercase letters, digits, underscores or hyphens.
- `pause EXPECTED_VERSION` and `resume EXPECTED_VERSION` append versions while retaining the policy settings. Resume releases the repository hold. A PR-level human hold or iteration stop still requires its existing lifecycle procedure.
- `show` returns the current version, settings and hold. After `STALE_VERSION`, review the latest settings before deciding whether to retry against the new version.
- Quiet hours form a daily interval in an IANA time zone. The start is inclusive and the end is exclusive. Overnight windows are supported. Omit all three fields to remove the interval. The worker reads the database clock and evaluates the resulting local time.
- `REQUIRE_PASSING_CI=true` requires persisted passing CI for the exact current head even on the first review. With `false`, existing CI gates still apply, including the mandatory native re-review and readiness gates. The policy does not define individual workflow identities.
- Query `repository_review_policy_versions` for the scope, version, operator actor, operation and server time. Revoke an operator by removing the scoped grant and disabling its login if appropriate. Access to a repository still requires the configured allowlist and enabled membership. The insert trigger checks the login, actor, membership, next version and hold semantics even when the login issues SQL directly.

## Effect on review work

The generic `REQUEST_REVIEW` outbox path, planned Codex re-reviews and standalone review requests use the policy. The outbox command and durable reservation each retain a scoped immutable version reference. The dispatcher checks both the original and live settings before a new effect. A policy change leaves receipt and evidence reconciliation available and never rewrites earlier action or audit metadata.

A repository pause parks unclaimed review commands. Resume makes them eligible for claim under the same original version and the applicable live gates. A pause that commits before an already-claimed command reaches its send fence can stop it. A known unsent command stopped at the fence can settle as `CANCELLED` with `POLICY_DENIED`. Such an action retains its deduplication key. A pause that waits for an effect already inside the fence takes effect after that bounded transaction. For native requests, inspect `NOT_SENT` reservations and their pinned policy version. Pending effects denied due to a changed policy or active quiet hours can settle under the existing terminal/retry semantics. Quiet hours suppress new planning, and expiration of the interval alone does not schedule another assessment.

The lifetime iteration cap counts retained reservations and outstanding or potentially applied review intents that do not already have a matching reservation. The generic dispatcher compares occupied slots with the lower of the pinned and live caps before a new send. Historical reservations and uncertain effects retain their slots. Provider authorization, SHA verification, PR readiness, idempotency and budget approval remain independent gates. Existing evidence ingestion, author notification and event delivery keep their own controls.

## Rollout and verification

V21 leaves policy references empty on pre-upgrade work. A historical receipt or deduplication key remains valid. A historical unsent permit cannot authorize a new effect and is never assigned a policy retroactively. Before enabling a review worker after rollout, provision the scoped grant and explicitly publish policy. To roll the binary back, stop review writers and dispatch, retain V21 and the audit data, and keep automation disabled on the older binary.

The local PostgreSQL suites cover concurrent operator edits, unauthorized roles and actors, immutable policy references, replay, quiet hours, denied sends, queue pause/resume and policy propagation through the planned Codex flow. GitHub/Codex integration tests use local fixtures. Live Codex entitlement and accounting, native MCP review dispatch and approval for additional providers remain unsupported by this feature. General usage-ledger and budget enforcement work is tracked in [issue #28](https://github.com/stevdrey/pr-review-orchestrator/issues/28).

On 2026-11-27, `./gradlew --offline clean build` passed all 419 tests with zero failures or skips on JDK 27 and disposable PostgreSQL. The operator command test used a dedicated database login with the documented grants. `git diff --check` passed.

The [PR #79 follow-up](https://github.com/stevdrey/pr-review-orchestrator/pull/79#discussion_r4725562728) reproduced the extra send on `b613e52`. The regression verifies that raising the cap from one to two allows the newer request to send while the older command settles as `CANCELLED / POLICY_DENIED` under its pinned cap. The durable dispatcher suite and `./gradlew --offline clean build` passed. The full suite ran 420 tests with zero failures or skips.
