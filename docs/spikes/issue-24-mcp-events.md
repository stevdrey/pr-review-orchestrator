# Issue #24 — MCP Events compatibility in Work and Dot

[Spike #24](https://github.com/stevdrey/pr-review-orchestrator/issues/24) · [EPIC 4](https://github.com/stevdrey/pr-review-orchestrator/issues/4) · [ADR 0006](../adr/0006-provider-neutral-agents-and-events.md)

**Result.** Work on the web and Dot completed the synthetic subscription lifecycle through a private Secure MCP Tunnel. The production service continues to use the server-local path by default. Workspace plugin and event-triggered task controls apply. [OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events)

## Initial client access

Checked on 2026-11-26 against repository baseline `33fdecf4f3b24a772ecd7cde8951da8c8a1106ff`.

| Path | Observation | Result |
| --- | --- | --- |
| Custom plugin setup | In the signed-in web session, **Plugins → Add → Add custom MCP server** opened the creation form. It offered **Server URL** and **Tunnel**. Selecting Tunnel displayed an empty **Tunnel ID** field. Authentication options were **OAuth**, **No authentication**, and **OAuth or no authentication**. | Setup form verified. No approved event-capable HTTPS endpoint or configured Secure MCP Tunnel was available for this run. The form was closed before creation and no discovery request was observed. |
| Work web | The **Work** selector activated and the composer displayed **Work with ChatGPT**. | Work mode verified. The test stopped before plugin discovery and subscription. Account-level event entitlement, `events/subscribe`, webhook receipt and `events/unsubscribe` remain unverified. |
| Work desktop | OpenAI documents **Work → Cloud** as an entry point. | Desktop event delivery was untested in this run. |
| Dot | **Dot** opened `/dots/home` with **Meet your dot** and **Continue**. | Onboarding was pending. The test stopped at that screen. Plugin usability and event entitlement were unverified. |

ChatGPT's documented integration accepts webhook delivery and verification. Polling, streaming event delivery, and the draft `gap` and `terminated` notifications are unsupported. Streamable HTTP can still carry the MCP requests for webhook subscriptions. The setup form and Work selector establish UI access only. [OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events) · [Dot workspace controls](https://help.openai.com/en/articles/20001554-managing-dots-in-chatgpt-workspaces)

## Live result with a private tunnel

On 2026-11-26 in Costa Rica, the official `tunnel-client` v0.0.21 reported `/readyz` HTTP `200`. ChatGPT connected the private plugin and showed `spike.sample` in its app catalog. The server observed `server/discover`, `tools/list` (an empty list), and `events/list`. The workspace owner authorized the personal tunnel without a client IP filter. Its only upstream was the synthetic server bound to `127.0.0.1`.

| Surface and filter | Live subscribe and delivery | Live unsubscribe and control |
| --- | --- | --- |
| Work web, `sample=work` | `events/subscribe` succeeded after a signed verification challenge returned HTTP `200`. The matching event `evt_b243bd44bd544da58ceaa8dad0144b90` received HTTP `200`, and the chat displayed `sample_f36aa1a91381` after refresh. | `events/unsubscribe` removed the original subscription. A fresh `work` event had zero targets and no delivery attempt. |
| Dot, `sample=dot` | `events/subscribe` succeeded after a separate signed verification challenge returned HTTP `200`. The matching event `evt_dc52b60aed054a9c8fcc960e1562dd9a` received HTTP `200`, and Dot displayed `sample_1e9de62d5449`. | `events/unsubscribe` removed the original subscription. A fresh `dot` event had zero targets and no delivery attempt. |

While the monitors were active, both `sample=other` control events had zero targets. Both clients confirmed that monitoring stopped. The sanitized server timeline below correlates the subscription operations with the emitted events. Times are UTC on 2026-11-27 (the evening of 2026-11-26 in Costa Rica).

| Surface | Subscription accepted | Event emitted → HTTP `200` | Matched unsubscribe | Post-stop emission |
| --- | --- | --- | --- | --- |
| Work web | `05:52:17.436` | `05:53:13.752` → `05:53:14.500` | `05:56:05.677` | `05:56:20.663`, zero targets |
| Dot | `05:54:22.102` | `05:54:44.898` → `05:54:45.530` | `05:55:39.755` | `05:56:20.663`, zero targets |

The test plugin was uninstalled, the publisher and tunnel client were stopped, and the Platform tunnel configuration remains available for reuse. The runtime key was configured with a one-day expiration. Work desktop, production OAuth, restart recovery, callback retry policy under service failure, and other workspace entitlements remain outside this live test.

## Documented wire contract

### Connect and authorize

Connect a trusted MCP endpoint via public HTTPS or Secure MCP Tunnel, create and install the plugin, and rescan after changing its event definitions. A tunnel requires a running client, a runtime credential, tunnel permissions and the appropriate workspace association. The tunnel handles the MCP request path. The publisher still needs outbound HTTPS to reach the webhook receiver. [Connect and test your plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt) · [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)

For repository-scoped events, use MCP OAuth 2.1 on the request path with protected-resource and issuer discovery, PKCE and a resource-bound access token. Validate the issuer, audience, time validity and scopes, resolve the connected principal, and check the principal's access to the installation, repository and requested PR filter. Apply this to discovery, subscription and unsubscription, and recheck access at delivery. OpenAI-managed mTLS, where configured, authenticates the MCP client. The callback signing secret authenticates outbound webhook messages. End-user access still depends on the OAuth principal and repository authorization. Mixed-mode event RPC authentication was untested. [Plugin authentication](https://developers.openai.com/plugins/build/auth) · [OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events)

### Discover, subscribe and unsubscribe

The documented extension uses JSON-RPC on the MCP endpoint. Advertise `capabilities.events` via `initialize` for initialization-era Streamable HTTP or via `server/discover` for MCP `2026-07-28`.

| RPC | Contract |
| --- | --- |
| `events/list` | Return `events[]` with `name`, `description`, `delivery: ["webhook"]`, `inputSchema` for filters and `payloadSchema` for the eventual `data`. For pagination use request `cursor` and response `nextCursor`. |
| `events/subscribe` | Accept `name`, `arguments`, `delivery: {mode, url, secret}`, and optional `cursor` and `ttlMs`. Decode the `whsec_`-prefixed base64 secret and require 24–64 bytes. After callback verification, return `id`, `refreshBefore`, `cursor` and `truncated`. Key creation and refresh by authenticated principal, canonicalized arguments, name and callback URL. |
| `events/unsubscribe` | Accept the original `name`, `arguments` and `delivery: {mode, url}`. Remove the principal's matching subscription idempotently and return `{}`. Stop new sends and pending retries for that subscription. |

Persist subscriptions across restarts and expire them at the granted `refreshBefore`. Refresh uses `events/subscribe` again and may rotate the signing key. Use finite TTLs for an initial adapter and stop on expiry, revocation or disconnect. If the adapter offers no replay, return `cursor: null`, with `truncated: false` at subscription creation, and document loss during gaps. If replay is added, use a cursor that cannot skip outstanding events and mark lost history as `truncated`. [OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events) · [MCP Events draft](https://github.com/modelcontextprotocol/experimental-ext-triggers-events/blob/main/docs/design-sketch-proposal.md)

### Verify the callback and deliver

- The publisher sends a signed HTTPS POST containing `type: "verification"` and a fresh, single-use, short-lived `challenge`. Activate only after a `2xx` with the exact echoed `challenge`, compared in constant time. An unusable callback produces JSON-RPC `-32015` with a categorized reason such as `challenge_failed` or `timeout`.
- Validate callback destinations for verification and delivery. Require HTTPS, reject credentials in URLs and redirects, block non-public addresses, and connect to an address validated at connection time while preserving the original TLS hostname. Apply an explicit egress policy and bounded timeouts. Keep the callback secret and full callback URL out of diagnostics.
- Send one JSON event per POST with a body of at most 262,144 bytes. Include `Content-Type: application/json`, `webhook-id`, `webhook-timestamp` (Unix seconds), `webhook-signature`, and `X-MCP-Subscription-Id`. For events, `webhook-id` equals `eventId`. For verification, use a separate unique message ID. The signature is `v1,` followed by the base64 HMAC-SHA256 of `webhook-id + "." + webhook-timestamp + "." + rawBody`, keyed by the decoded secret. The subscription ID header is outside that signature input.
- When `events/subscribe` refreshes a subscription with a replacement secret, activate the new key and retain the previous key for a short overlap. During that window, sign the same message ID, timestamp, and raw body with each key. Send both signatures in one `webhook-signature` header, separated by a space (`v1,<new-signature> v1,<previous-signature>`). Use only the new key after the overlap ends. [OpenAI MCP Events refresh contract](https://developers.openai.com/plugins/build/mcp-events#refresh-a-subscription)
- A `2xx` acknowledges receipt. ChatGPT processes the event asynchronously and may batch separate deliveries into one task run. Allow out-of-order delivery and duplicates. Use bounded exponential backoff for transient failures, preserve the event ID and re-sign each attempt. OpenAI specifies no retry on `410` or `413`. Record receipt separately from a verified Work or Dot task result.

[OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events) · [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)

### Event envelope

The example below maps a proposed local PR event to the documented webhook envelope. Use the ISO 8601 occurrence time as `timestamp`. Reserve a top-level `type` for protocol control messages and keep application fields within `data`.

```json
{
  "eventId": "evt_001",
  "name": "pr.ready.for.validation",
  "timestamp": "2026-11-26T19:00:00Z",
  "data": {
    "schemaVersion": 1,
    "installationId": 101,
    "repositoryId": 202,
    "pullRequestNumber": 3,
    "headSha": "0123456789abcdef0123456789abcdef01234567",
    "stateVersion": 7,
    "traceId": "trace_001"
  },
  "cursor": null
}
```

The proposed filter uses `installationId`, `repositoryId` and `pullRequestNumber`. Validate positive integer identifiers, integer versions, a full SHA and a bounded trace ID. Close both schemas to unknown properties. Exclude diff, source, comment bodies, credentials and instructions from published data. `eventId` identifies the durable transition independently of provider subscription and retry identities. The actual local envelope and persistence belong to [#25](https://github.com/stevdrey/pr-review-orchestrator/issues/25).

## Server-local fallback

The GitHub webhook inbox, PostgreSQL-backed review state, durable dispatch and opt-in GitHub author notices can operate without MCP credentials or a ChatGPT client. Preserve that path as the default. The outbox currently queues commands and author notices. The provider-neutral transition publisher is scoped to #25, and richer MCP tools and gated commands to [#26](https://github.com/stevdrey/pr-review-orchestrator/issues/26).

When #25 introduces domain events, record a redacted transition and its stable ID in the same transaction as the state update. Consume it from the durable local queue. Keep MCP translation, subscriptions, callback verification and retry state in an optional adapter. With missing credentials, unresolved capability, expired access or an unavailable receiver, keep processing local events without a provider send. This path uses verified GitHub webhook ingress and local queue processing without adding continuous GitHub polling. An event or a webhook receipt carries no authority to review, write to GitHub, change review state or approve a merge. [Durable dispatch](../development/durable-dispatch.md) · [Author notices](../development/author-notifications.md)

## Reproducible evidence

```sh
python3 -B docs/spikes/tests/test_issue_24_mcp_events.py -v
```

Eight tests passed using Python 3.15.2 and the standard library. Five use in-process JSON-RPC and a signed loopback callback to cover discovery, the verification challenge, repeat subscriptions, owner-scoped removal, filtering, receipt, no sends after unsubscribe, redelivery deduplication, and rejection of tampered bytes. Two exercise the tunnel server over MCP HTTP with simulated callbacks and check that it rejects unauthorized hosts and nonpublic DNS results. A separate test runs the real `post_callback` with a simulated HTTPS transport and checks the exact body, headers and signature against a fixed vector. OAuth, external TLS and callbacks, key rotation, persistence, and client actions are outside these local checks.

`./gradlew --offline clean build` passed 370 Java tests with zero failures, errors or skips. The spike uses a separate Python fixture and introduces no Java dependency or runtime wiring.

## Repeat the live check

### Disposable tunnel server

From the repository root, start the loopback fixture. Leave this terminal open for operator commands.

```sh
python3 -B docs/spikes/tests/issue_24_tunnel_server.py --port 8765
```

It only exposes the `spike.sample` event with `work`, `dot` and `other` filters. Type one of those values in the server terminal to emit a fresh synthetic ID. `quit` stops the server and drops its in-memory subscriptions. The HTTP interface has no event-emission endpoint, repository access or write tools. Use it with a private, single-operator test tunnel. This fixture relies on private tunnel access plus the callback challenge and caps subscriptions at 30 minutes. Use end-user authorization and durable state for any repository-scoped adapter.

Use the [official `tunnel-client` release](https://github.com/openai/tunnel-client/releases/latest). In a separate local Bash terminal, set `CONTROL_PLANE_TUNNEL_ID` to the test tunnel ID from Platform, then enter the restricted runtime key without printing it. Generate and run an isolated profile using that terminal.

```bash
read -r -s -p 'Tunnel runtime key: ' CONTROL_PLANE_API_KEY
printf '\n'
export CONTROL_PLANE_API_KEY
spike_profile_dir=$(mktemp -d "${TMPDIR:-/tmp}/issue-24-mcp-profile.XXXXXXXX")
tunnel-client init --sample sample_mcp_remote_no_auth --profile issue-24-synthetic \
  --profile-dir "$spike_profile_dir" \
  --tunnel-id "$CONTROL_PLANE_TUNNEL_ID" --mcp-server-url http://127.0.0.1:8765/mcp \
  --health-listen-addr 127.0.0.1:0
tunnel-client doctor --profile issue-24-synthetic --profile-dir "$spike_profile_dir" --explain
tunnel_health_file=$(mktemp "${TMPDIR:-/tmp}/issue-24-mcp-health.XXXXXXXX")
printf 'Health URL file %s\n' "$tunnel_health_file"
tunnel-client run --profile issue-24-synthetic --profile-dir "$spike_profile_dir" --health.url-file "$tunnel_health_file"
rm -f "$tunnel_health_file"
unset CONTROL_PLANE_API_KEY
```

The runtime key needs **Tunnels Read + Use**. Creating a tunnel requires **Tunnels Read + Manage**. Confirm the target ChatGPT workspace is associated with the tunnel and use the approved client-network scope. [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)

### Work and Dot lifecycle

1. Verify tunnel readiness with `doctor` and the running client's `/readyz`. Capture only sanitized RPC method names, timestamps, result codes, generated IDs, and callback status. Keep secrets, URLs and raw authorization headers out of the evidence.
2. Create and install it as a private custom MCP plugin in **Tunnel** mode with **No authentication** for this fixture's synthetic data. Capture the discovery handshake, `events/list` and the event on the plugin page. Verify that the workspace permits event-triggered tasks. In Work on the web, ask to monitor `spike.sample` with `sample=work` and report only `sampleId` in that chat. In an onboarded Dot, use `sample=dot` with the same instructions. Work desktop can use the Work filter with Cloud selected in a separate run.
3. Correlate `events/subscribe`, the challenge echo, and the subscription ID. Enter `other` and the surface's filter value in the server terminal. Record zero deliveries for `other`, the matching event's HTTP receipt and the sample ID surfaced by the client. A provider response without correlated RPC and HTTP evidence leaves the path unverified.
4. Stop monitoring, capture `events/unsubscribe`, inject another matching event and confirm zero new delivery attempts. Verify that restart, refresh, disconnect and revocation respect the subscription state. If a step fails, record the surface, configuration, last successful step and sanitized error. Remove the test plugin and stop the test endpoint after the run.
