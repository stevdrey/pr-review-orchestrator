# MCP tools

The optional `POST /mcp` endpoint implements the JSON response form of [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports). It supports initialization, ping, tool discovery and tool calls. It supports protocol versions `2025-11-25`, `2025-06-18` and `2025-03-26`. The transport keeps no MCP session ID and opens no SSE stream. An authenticated client can supply its negotiated version in `MCP-Protocol-Version` and advertise both `application/json` and `text/event-stream` in `Accept`. The endpoint refuses requests with an `Origin` header and expects UTF-8 `application/json` bodies up to 16 KiB.

## Enable and grant access

1. Set `ORCHESTRATOR_MCP_ENABLED=true`. Bind a local deployment to loopback, or use a private TLS ingress that limits request rates and timeouts and redacts Authorization headers and bodies. Leave the endpoint disabled on public deployments.
2. Ensure the PR already exists in the local review state and the installation/repository pair is deployment-allowlisted, active and enabled. Verify the user's repository access before granting reads and verify maintainer authorization and explicit consent before granting a pause.
3. From a trusted operator connection, provision a fresh grant for that actor and PR. Generate a 32-byte cryptographically random token encoded as 64 lowercase hexadecimal characters. Store the SHA-256 digest of the **encoded token string**, without a trailing newline. Deliver the token securely to the intended client for its `Authorization: Bearer <token>` header on every POST. Use a dedicated credential for this service.
4. Revoke the grant by setting `revoked_at` when the user loses access or the session ends. PostgreSQL checks expiry and revocation during each operation. The operator must update access when GitHub permissions change. There is no GitHub user permission synchronization, OAuth flow or self-service grant API.

A read grant for a known PR can be created with the following parameterized statement. The placeholders represent the digest, verified actor ID, installation ID, repository ID and PR number.

```sql
INSERT INTO mcp_tool_sessions
    (token_sha256, actor_id, installation_id, repository_id, pr_number, expires_at)
VALUES ($1, $2, $3, $4, $5, clock_timestamp() + interval '5 minutes');
```

To authorize a pause, create a separate grant and include all four approval fields in the insert. Set `approved_tool` to `pause_cycle`. Use a fresh `command_id` UUID and bind `approved_head_sha` and `approved_version` to the full SHA and local state version shown to the consenting operator. The database caps every grant at 15 minutes. The bearer credential can exercise that exact approval. Keep it in the client's secret configuration.

## Tool contract

Every tool requires `installationId`, `repositoryId`, `prNumber` and the full lowercase `headSha`. Commands also require `commandId` and `expectedVersion`. Discovery advertises the input constraints through `tools/list`. Unknown arguments, malformed values and missing SHA are rejected.

| Tool | Result |
| --- | --- |
| `get_pr_status` | Current local state, version, CI and clean-stage flags for the expected SHA. |
| `list_findings` | Up to 50 redacted findings for the PR and SHA, including stable IDs where available and separate thread-resolution and finding status fields. Pass `nextCursor` as `after` to continue. Pages reflect current local data at each call. |
| `pause_cycle` | Applies the existing local pause transition once. A matching retry on the same head returns `ALREADY_APPLIED` and the applied version. The normal PR domain event is committed with the transition. A pause gates subsequent review work and cannot cancel a provider request already sent. |
| `request_review` | Validates the scoped grant, exact approval, head and version. Returns `PROVIDER_UNAVAILABLE` for a valid request because there is no approved MCP provider, user credential and budget integration. No review is enqueued or sent. |

The SHA is the locally reconciled head. These tools do not fetch GitHub or establish a fresh remote observation. No tool starts a provider review, changes GitHub, merges, resumes a cycle or grants access. Local pause remains available if providers and the optional event publisher are disabled.

## Audit and operation

Authenticated, well-formed calls within scope write bounded entries to `audit_entries` with the delegated actor ID, tool, installation/repository/PR, expected SHA, outcome and correlation ID. A command uses its approved or attempted UUID as the correlation ID. A pause and its audit entry commit together. Invalid credentials, cross-scope targets and malformed requests are rejected before tool auditing. Avoid logging raw tokens, request bodies or provider data at the ingress. Findings omit comment bodies, GitHub source IDs, diffs, paths and audit reasons.

Unauthorized requests return HTTP 401 with a generic bearer challenge. Tool errors return an MCP result with `isError` and a stable result code in `structuredContent`; valid results include an equivalent text representation. A database or audit failure returns HTTP 503. Set the enable flag to false and revoke delegated grants to roll back access. The additive migration can remain applied.

Verification passed with `./gradlew --offline test --tests '*McpIntegrationTest' --tests '*DatabaseIntegrationTest'`. The tests use a native HTTP fixture client and disposable PostgreSQL. They exercise the disabled-by-default route, migration upgrades, authentication, grant confinement, full-SHA and version checks, redacted pagination, concurrent replay, audit rollback and the unavailable review provider.

The full `./gradlew --offline build` passed all 404 tests.

The synthetic subscription tested in Issue #24 used a separate endpoint. Work and Dot compatibility with this private bearer endpoint has not been verified. [Issue #24 evidence](../spikes/issue-24-mcp-events.md)
