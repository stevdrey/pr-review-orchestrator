# Issue #11 — GitHub App registration and live verification

Verified on **2026-11-13 (America/Costa_Rica)** against implementation commit `3bd5b45e1ad3fe6ca6a19e42bcc285d6899716e1`. This records one operator-approved development installation, not a production deployment or automated review service.

## Registered identity and approved scope

| Item | Verified value |
| --- | --- |
| App | `stevdrey-pr-review-orchestrator` |
| App ID | `5753004` |
| Client ID (public identifier) | `Iv23lihJu8nClwC6B6sk` |
| Owner | `stevdrey` / account ID `3165632` |
| Installation ID | `181778785` |
| Installation mode | Only selected repositories |
| Permissions | `actions`, `checks`, `issues`, `metadata`, `pull_requests`: **read** |
| Webhooks | Inactive; no event subscriptions |
| OAuth | No callback, OAuth-on-install disabled, no client secret created |

The private App was registered and installed through GitHub settings. The operator explicitly approved the following three repositories; authenticated App/installation responses confirmed the expected App and account identities and read-only permissions:

| Repository | Stable repository ID |
| --- | --- |
| `stevdrey/pr-review-orchestrator` | `1457036208` |
| `stevdrey/dokene` | `1344259200` |
| `stevdrey/monada-neuron` | `1208930543` |

Operator controls: [App settings](https://github.com/settings/apps/stevdrey-pr-review-orchestrator), [installation settings](https://github.com/settings/installations/181778785). These settings require the owner's GitHub session.

## Protected local setup

The operator generated and downloaded the RSA private key. Its public-key fingerprint was checked against GitHub's registered key without exposing private material. The downloaded file was restricted to mode `0600`, converted to PKCS#8 in the ignored `secrets/` directory (mode `0700`, key mode `0600`), and referenced by an ignored `application-local.yaml` (mode `0600`). No key or token was committed or copied into a prompt, issue, PR or log.

The local additional configuration enables the token client and allows exactly the three approved installation/repository pairs. The Compose PostgreSQL development database was started, Spring Boot applied Flyway V1, and operator-approved ACTIVE/enabled memberships were provisioned with metadata-only audit entries. Existing membership records were verified, rather than blindly reactivated, when repeating the probe. `.env` contains only the documented disposable local database settings.

The application context was shut down after the probe. PostgreSQL remains available for local development; no public service, webhook receiver or review worker was deployed. To start locally using this protected configuration:

```sh
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.config.additional-location=file:./application-local.yaml'
```

## Actual checks and results

A temporary operator probe was compiled against the built application and its pinned runtime dependencies. It called the real `GitHubAppJwt`, `GitHubInstallationTokens`, Spring configuration, `RepositoryAuthorization` and JDBC adapter. Its source/log remain local operator artifacts, not CI fixtures.

Commands executed from the repository root:

```sh
javac -cp 'build/classes/java/main:build/live-probe/BOOT-INF/lib/*' \
  -d build/live-probe /tmp/GitHubAppLiveProbe.java
java -cp 'build/live-probe:build/classes/java/main:src/main/resources:build/live-probe/BOOT-INF/lib/*' \
  io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubAppLiveProbe
```

Final probe source SHA-256: `71a29b7d11748db624409ffd35c9f841c7bf610fef227f129c59f78ccddad55d`. The temporary probe is not a shipped CLI and its local paths are not expected on other machines.

| Check | Observed result |
| --- | --- |
| Authenticated `GET /app` | Expected App ID, slug, client ID, five read permissions and empty events |
| Authenticated `GET /app/installations/181778785` | Expected App/account IDs, selected-repository mode, no suspension and five read permissions |
| Actual installation-token adapter | All four read operations succeeded for all three repositories: **12 combinations** in the final completed probe |
| Token scope | Each response and `GET /installation/repositories` named exactly the requested single repository |
| Local foreign installation / unlisted repository | Denied by the application gate |
| Local suspension / disabled membership | Denied; the approved ACTIVE/enabled state was restored afterward |
| Token cleanup | Every minted probe token was submitted to `DELETE /installation/token`; GitHub returned HTTP **204** |
| Immediate token invalidation | **Not established:** subsequent `GET /installation/repositories` returned HTTP **200**, including with unique non-secret query parameters and `Cache-Control: no-cache` |
| Secret handling | Outputs contained only allowlisted identifiers, operation names, status codes and outcomes; no credentials were printed or persisted |

Earlier bounded probes stopped at the post-revocation assertion. They had already received HTTP 204 for their token-cleanup calls. The completed probe distinguishes successful scope/authorization checks from the unproven invalidation check; it does not turn an HTTP 200 into a claim that revocation worked.

## Known limits and rollout boundary

GitHub's [revocation contract](https://docs.github.com/en/rest/apps/installations#revoke-an-installation-access-token) says a revoked token is invalidated. Its repository-list endpoint also documents public-resource access without authentication. The observed HTTP 200 does not establish immediate invalidation or explain its cause. No claim is made about remote revocation propagation or rejection of revoked credentials on private resources. Token values were discarded; the application does not cache or persist them. If immediate remote revocation is a rollout requirement, resolve this limitation before relying on it as a control. Local authorization remains independently enforced before token issuance and before returning credentials.

Remote installation suspension/removal, live signing-key replacement, webhook-secret rotation and a production secret manager were not tested. Local generated-key replacement and redacted failure paths are covered by the 38-test build recorded in [PR #45](https://github.com/stevdrey/pr-review-orchestrator/pull/45); that is distinct from live operator key rotation. Webhook ingestion (#12/#13), optional user OAuth, Codex entitlement/billing attribution (#42), review triggering and merge automation are outside this registration increment. No PR comments, review requests, repository writes or merges were performed by the live probe.

The App registration and installation are complete. The implementation remains in PR #45 until reviewed and merged; this evidence alone does not close or merge the implementation issue.
