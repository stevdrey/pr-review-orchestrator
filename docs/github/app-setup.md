# GitHub App registration and installation authorization (Issue #11)

## Delivered boundary

The service now has an installation/repository authorization gate, a PostgreSQL membership adapter, RS256 App authentication and a repository-scoped **read-only** installation-token client. Token issuance is disabled unless `orchestrator.github.enabled=true`; the deployment allowlist is empty by default. No HTTP token endpoint, webhook receiver, installation auto-enrollment, scheduled GitHub calls, comment writer or OAuth callback is supplied. Merely starting the service never contacts GitHub.

This is the implementation and operator registration contract for [issue #11](https://github.com/stevdrey/pr-review-orchestrator/issues/11), under accepted [ADR 0002](../adr/0002-github-app-webhook-trust.md). The [registration evidence](issue-11-registration-evidence.md) records the operator-approved development App, installation on three repositories and live scoped-token checks. Remote token invalidation remains unproven; this is distinct from local authorization checks and does not certify other installations. Webhook signature/replay processing belongs to #12/#13.

## Registration contract and minimum permissions

Register a private development App under the intended operator account using GitHub **Settings → Developer settings → GitHub Apps → New GitHub App**. Set its homepage to this project and choose a unique name. Initially leave webhooks inactive and user authorization callbacks unset. Generate its private key through the App settings. Record App ID, client ID and App slug as non-secret operator metadata. Install it with **Only select repositories**, initially a disposable test repository; explicitly adding other repositories requires operator approval and both authorization layers below.

Configure these repository permissions for the read-only integration contract; grant no organization or account permissions. Permissions correspond to GitHub's API names.

| Permission | Access | Operation / reason | Token operation |
| --- | --- | --- | --- |
| `metadata` | Read (implicit) | Stable repository identity | Included with each token |
| `pull_requests` | Read | PR head, diff, review, inline comment and review-thread reads | `PULL_REQUESTS` |
| `issues` | Read | General issue/PR conversation comments, kept distinct from review threads | `COMMENTS` |
| `checks` | Read | Check runs and suites for the reviewed SHA | `CHECKS` |
| `actions` | Read | Workflow-run status for the reviewed SHA | `WORKFLOWS` |

Every issued token requests **one repository ID** and **only one operation's permission plus metadata**. There is no arbitrary permission input or write operation. Do not grant Contents, Administration, Workflows write, Checks write, organization membership, email access, secrets, deployments or merge authority. PR diffs do not require cloning repository contents in this increment.

A later comment/notification implementation must justify and explicitly approve upgrading `pull_requests` to write for PR conversation writes (or `issues` write for issue writes), then add an action-specific token policy with current-SHA/idempotency gates. Do not enable these permissions preemptively. Do not use a user token or a PAT to compensate for missing App permissions.

## Event subscription contract (activate only with verified ingress)

When #12/#13 provide raw-body HMAC verification, payload limits, delivery deduplication and durable acceptance at a real HTTPS endpoint, configure a separate random webhook secret in a secret manager and activate SSL verification. Subscribe only to:

| Events | Required permission | Intended use |
| --- | --- | --- |
| `installation`, `installation_repositories` | Installation lifecycle events | Revoke/suspend/remove membership; never auto-approve new membership |
| `pull_request` | Pull requests read | Open, ready, synchronize, reopen and close transitions |
| `pull_request_review`, `pull_request_review_comment`, `pull_request_review_thread` | Pull requests read | Review evidence and thread state, without treating resolution as proof of a fix |
| `issue_comment` | Issues read | General PR comments, including edits/deletions; filter out ordinary issues |
| `check_run`, `check_suite` | Checks read | Completed CI evidence; no re-request/write actions |
| `workflow_run` | Actions read | Workflow completion and SHA correlation |

Do not subscribe to `push` or add Contents access just to detect PR updates: `pull_request.synchronize` covers this contract. Installation-level lifecycle events may have no repository; they need a separate verified control path in future ingress. Unknown installation events cannot authorize repository work. Capture sanitized fixtures to verify actions and permissions before activation.

## Two independent local authorization layers

`RepositoryAuthorization` requires a stable `(installationId, repositoryId)` pair in deployment configuration **and** an existing database membership whose installation is `ACTIVE` and repository is `enabled=true`. Missing/disabled/revoked/suspended records, crossed pairs, invalid IDs and authority errors deny access. No repository-name or actor-name matching is used. DB errors are reduced to a generic denial without nested diagnostics. Checks are performed before any token HTTP request and repeated before releasing a returned token.

Use trusted operator administration to verify the App's installation belongs to the intended account and repository before provisioning rows. Confirm installation/App/account IDs using GitHub App settings or its authenticated installation API and repository IDs using GitHub's repository API, never arbitrary webhook/comment text. Neither public repository visibility nor an App installation constitutes consent to the local allowlist. The service does not expose an enrollment endpoint or discover/enroll installations automatically.

Example additional configuration, stored outside source control (the numbers are synthetic; replace them):

```yaml
orchestrator:
  github:
    enabled: true
    client-id: ${GITHUB_APP_CLIENT_ID}
    private-key-file: ${GITHUB_APP_PRIVATE_KEY_FILE}
    repositories:
      101: [1001]
      202: [2002]
```

Load it with `--spring.config.additional-location=file:/secure/config/github-app.yaml`. These pairs do not permit `(101, 2002)` or `(202, 1001)`. Removing a configuration pair requires a service restart; for immediate local denial, disable its database membership. An empty map denies all. `enabled` controls availability of the token client, not the allowlist gate, which is wired independently of the GitHub adapter. Each listed installation must name at least one repository ID; an empty or null entry fails startup.

The existing V1 tables are reused without migration. Provision memberships through an audited privileged database session, keeping secrets out of the SQL; for example, with **synthetic** IDs:

```sql
BEGIN;
INSERT INTO installations (installation_id, account_id) VALUES (101, 1);
INSERT INTO installation_repositories (installation_id, repository_id) VALUES (101, 1001);
-- Only after checking remote identity/membership and explicit operator consent:
UPDATE installation_repositories SET enabled = true
WHERE installation_id = 101 AND repository_id = 1001;
COMMIT;
```

To stop a repository, commit `enabled=false` for that exact pair; to stop an installation, commit its `status='SUSPENDED'` or `'REVOKED'`. Never blindly upsert an installation back to ACTIVE during reconciliation. These rows are trusted operator policy; future webhook processing must not allow a newly signed `installation.created` event to grant consent.

## Credentials, expiry and rotation

Use a secret manager or restrictive mounted file for the App private key, separate from the webhook HMAC secret and any future user credentials. GitHub-generated RSA PEM keys must be converted to unencrypted **PKCS#8** for this Java client, in a protected operator session:

```sh
umask 077
openssl pkcs8 -topk8 -nocrypt -in /secure/github-original.pem -out /secure/github-app.pkcs8.pem
```

Mount the resulting file read-only for the application identity and point `GITHUB_APP_PRIVATE_KEY_FILE` at it. The key must be RSA with at least 2048 bits. Never put key contents in configuration, an issue, a shell argument, Gradle output, AI prompts or a committed file. `.gitignore` excludes PEM/key files and local secret configuration. Enabled startup rejects an invalid/unreadable key; disabled startup needs no GitHub credentials.

For each issuance, the client reloads the key and signs a JWT with `iat=now-60s`, `exp=now+540s` and client ID as issuer. It calls only `https://api.github.com/app/installations/{id}/access_tokens`, using the pinned REST API version `2026-03-10`. HTTPS, fixed host, no redirects, a connection timeout, an overall exchange deadline, a 64 KiB response cap and no automatic retries protect the credential exchange. GitHub checks the installation belongs to this App and can access the requested repository. Local database status is not a replacement for that remote check.

The response must name exactly the requested repository and read permissions and contain a token with more than 60 seconds remaining. A token is held only in memory and explicitly revealed only to construct an API Authorization header. It is never cached or persisted, and its `toString()` is redacted. If a minted token fails response validation or the post-exchange authorization recheck, the client revokes it best-effort (`DELETE /installation/token`) before failing; a post-exchange denial is still reported as a `SecurityException`, not as unavailability. Failures discard remote bodies, request details and nested exception causes. Do not enable HTTP header/body wire logging, heap dumps, credential serialization or debug inspection in production. Future callers must recheck authorization and expiry immediately before each API action; receiving a token is not a durable authorization grant.

To rotate an App key: generate a replacement in GitHub, convert it in protected storage, atomically replace the mounted PKCS#8 file, perform a controlled read-only probe, then delete the old key from GitHub. Every subsequent issuance uses the new file without restart. An unreadable/missing replacement fails closed. Already issued installation tokens can outlive key deletion: revoke affected tokens/installation separately if compromise is suspected; deleting a signing key alone is not token revocation. There is no local token cache to purge. Keep production key rotation evidence in private operator audit storage, recording identifiers/timestamps/results rather than credentials.

Webhook secret rotation is an operator contract only until #12 implements HMAC verification: stage the new secret in protected storage, coordinate the GitHub setting and receiver deployment, support only a bounded old/new overlap if explicitly implemented, then remove the old secret and verify rejection. This service currently neither consumes nor validates webhook secrets; keep webhooks inactive.

## Optional user authorization (disabled, documented contract only)

Installation tokens act as the App. An owner-attributed action requires separate explicit GitHub App user consent; no OAuth callback, client secret, user-token persistence or fallback is implemented here. Before adding it, require an exact HTTPS redirect URI, single-use expiring state bound to the authenticated operator/session and PKCE where supported. Verify the returned `/user` identity against the consenting operator, verify installation/repository access, encrypt access/refresh tokens at rest in a secret manager, rotate expiring credentials and support revocation. User access is the intersection of user and App permissions and still needs the local allowlist. A callback or installation event must never silently enable this flow.

App comment identity does not establish Codex acceptance or subscription billing. The [issue #14 spike](../spikes/issue-14-codex-attribution.md) and [issue #42](https://github.com/stevdrey/pr-review-orchestrator/issues/42) track that evidence. Do not issue owner-attributed review triggers until all required identity, entitlement, usage, budget and policy checks pass.

## Verification and rollout limits

- `./gradlew test --tests '*GitHub*' --tests '*RepositoryAuthorization*'` exercises paired allowlists, default-disabled configuration, RS256 signature verification, JWT lifetime, single-repository/operation scoping, key replacement, sanitized failure stack traces, invalid/expired responses, redirects/API errors, network failure and revocation during exchange. Keys/tokens are generated synthetic fixtures only.
- `./gradlew clean build` also runs PostgreSQL/Testcontainers tests for default-disabled membership, ACTIVE/SUSPENDED/REVOKED transitions and exact paired authorization, alongside existing migration/schema/startup checks. Missing Docker fails the build.
- Live App registration, installation/permission consent and real scoped token minting are recorded separately in the [development registration evidence](issue-11-registration-evidence.md). They are **not verified by local tests**. Immediate remote token invalidation, remote suspension and deployed secret-manager rotation remain unverified. There are no actual GitHub or provider calls during tests. No user OAuth or webhook-HMAC behavior is claimed.

Enable only in an operator-controlled test environment after provisioning both layers and credentials. Roll back by disabling affected database memberships first, disabling `orchestrator.github.enabled`, restarting, and revoking remote credentials if needed. No schema rollback or provider spending is introduced by this increment.

## Primary references

GitHub documentation checked on 2026-11-13: [permissions](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app), [webhook events](https://docs.github.com/en/webhooks/webhook-events-and-payloads), [JWT claims](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app), [installation-token scope](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app), [installation API](https://docs.github.com/en/rest/apps/apps), [App user authorization](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-on-behalf-of-a-user).
