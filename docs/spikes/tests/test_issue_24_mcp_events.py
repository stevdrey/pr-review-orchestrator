"""Synthetic MCP Events contract probe. The callback accepts loopback traffic only."""

import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, redirect_stdout
from datetime import datetime, timezone
import hashlib
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer, ThreadingHTTPServer
from io import StringIO
import json
from threading import Event, Thread
import unittest
from unittest.mock import patch
from uuid import uuid4

import issue_24_tunnel_server as tunnel


EVENT_NAME = "spike.sample"
SIGNING_TIME = 1795737600
KEY = bytes(range(32))  # Public test vector, never a credential.
EXPECTED_SIGNATURE = "v1,qHO0scQ6Pz3yEJ5mbso0XKDQrMB6qQXm9a5l3V9uwE8="
SCHEMA = {
    "type": "object",
    "properties": {"sample": {"type": "string"}},
    "required": ["sample"],
    "additionalProperties": False,
}


def signed_post(message_id, payload, subscription_id, timestamp=SIGNING_TIME):
    body = json.dumps(payload, separators=(",", ":")).encode()
    signed = f"{message_id}.{timestamp}.".encode() + body
    signature = base64.b64encode(hmac.digest(KEY, signed, "sha256")).decode()
    return body, {
        "Content-Type": "application/json",
        "webhook-id": message_id,
        "webhook-timestamp": str(timestamp),
        "webhook-signature": f"v1,{signature}",
        "X-MCP-Subscription-Id": subscription_id,
    }


class Callback(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != "/callback":
            self.send_error(404)
            return
        body = self.rfile.read(int(self.headers["Content-Length"]))
        message_id = self.headers["webhook-id"]
        timestamp = self.headers["webhook-timestamp"]
        signed = b".".join((message_id.encode(), timestamp.encode(), body))
        expected = "v1," + base64.b64encode(hmac.new(KEY, signed, hashlib.sha256).digest()).decode()
        if not hmac.compare_digest(expected, self.headers["webhook-signature"]):
            self.send_error(401)
            return
        payload = json.loads(body)
        reply = b""
        if payload.get("type") == "verification":
            self.server.challenges += 1
            challenge = payload["challenge"] if self.server.verify else "wrong-challenge"
            reply = json.dumps({"challenge": challenge}).encode()
        else:
            if payload["eventId"] != message_id:
                self.send_error(400)
                return
            identity = (self.headers["X-MCP-Subscription-Id"], message_id)
            self.server.received.setdefault(identity, payload)
        self.send_response(200 if reply else 204)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)


class ContractProbe(unittest.TestCase):
    def setUp(self):
        self.callback = HTTPServer(("127.0.0.1", 0), Callback)
        thread = Thread(target=self.callback.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        self.addCleanup(self.callback.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(self.callback.shutdown)
        self.callback.challenges = 0
        self.callback.received = {}
        self.callback.verify = True
        self.url = f"http://127.0.0.1:{self.callback.server_port}/callback"
        self.subscriptions = {}
        self.arguments = {"sample": "alpha"}
        self.destination = {"mode": "webhook", "url": self.url}

    def post(self, body, headers):
        # No DNS, proxies, redirects or dialing the client-supplied URL.
        connection = http.client.HTTPConnection("127.0.0.1", self.callback.server_port, timeout=2)
        try:
            connection.request("POST", "/callback", body, headers)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def rpc(self, method, params=None, *, principal="test-user"):
        request = json.loads(json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}))
        key, value = self.dispatch(request["method"], request["params"], principal)
        return json.loads(json.dumps({"jsonrpc": "2.0", "id": request["id"], key: value}))

    def dispatch(self, method, params, principal):
        # In-process JSON-RPC fixture with a simulated authenticated principal.
        if method == "initialize":
            return "result", {"protocolVersion": "2025-03-26", "capabilities": {"events": {}},
                              "serverInfo": {"name": "issue-24-probe", "version": "0.1.0"}}
        if method == "events/list":
            return "result", {"events": [{"name": EVENT_NAME, "description": "A synthetic sample",
                                          "delivery": ["webhook"], "inputSchema": SCHEMA, "payloadSchema": SCHEMA}]}

        destination = params["delivery"]
        sample = params["arguments"]["sample"]
        if destination["mode"] != "webhook" or destination["url"] != self.url:
            return "error", {"code": -32602, "message": "Fixture requires its own callback"}
        self.assertEqual(EVENT_NAME, params["name"])
        identity = (principal, destination["url"], params["name"], sample)
        if method == "events/unsubscribe":
            self.subscriptions.pop(identity, None)
            return "result", {}

        self.assertEqual("events/subscribe", method)
        self.assertEqual("whsec_" + base64.b64encode(KEY).decode(), destination["secret"])
        subscription_id = hashlib.sha256(json.dumps(identity).encode()).hexdigest()
        if identity not in self.subscriptions:
            challenge = uuid4().hex
            body, headers = signed_post(uuid4().hex, {"type": "verification", "challenge": challenge}, subscription_id)
            status, reply = self.post(body, headers)
            if status != 200 or not hmac.compare_digest(challenge, json.loads(reply)["challenge"]):
                return "error", {"code": -32015, "message": "CallbackEndpointError",
                                 "data": {"reason": "challenge_failed"}}
            self.subscriptions[identity] = subscription_id
        expires = datetime.fromtimestamp(SIGNING_TIME + 3600, timezone.utc).isoformat()
        return "result", {"id": subscription_id, "refreshBefore": expires, "cursor": None, "truncated": False}

    def subscribe(self, destination=None):
        delivery = dict(destination or self.destination, secret="whsec_" + base64.b64encode(KEY).decode())
        return self.rpc("events/subscribe", {"name": EVENT_NAME, "arguments": self.arguments,
                                             "delivery": delivery, "cursor": None})

    def unsubscribe(self, principal="test-user"):
        return self.rpc("events/unsubscribe", {"name": EVENT_NAME, "arguments": self.arguments,
                                               "delivery": self.destination}, principal=principal)

    def event(self, sample="alpha", event_id="evt-001"):
        return {"eventId": event_id, "name": EVENT_NAME, "timestamp": "2026-11-27T00:00:00Z",
                "data": {"sample": sample}, "cursor": None}

    def publish(self, event, timestamp=SIGNING_TIME):
        return [self.post(*signed_post(event["eventId"], event, sub_id, timestamp))[0]
                for (_, _, name, sample), sub_id in self.subscriptions.items()
                if name == event["name"] and sample == event["data"]["sample"]]

    def test_discover_subscribe_filter_notify_and_unsubscribe(self):
        self.assertIn("events", self.rpc("initialize")["result"]["capabilities"])
        definition, = self.rpc("events/list")["result"]["events"]
        self.assertEqual((EVENT_NAME, ["webhook"]), (definition["name"], definition["delivery"]))
        subscription = self.subscribe()["result"]
        self.assertEqual(subscription["id"], self.subscribe()["result"]["id"])
        self.assertEqual(1, self.callback.challenges)
        self.assertEqual([], self.publish(self.event("beta")))
        self.assertEqual({}, self.unsubscribe("other-user")["result"])
        self.assertEqual([204], self.publish(self.event()))
        self.assertEqual(self.event(), self.callback.received[(subscription["id"], "evt-001")])
        self.assertEqual({}, self.unsubscribe()["result"])
        self.assertEqual({}, self.unsubscribe()["result"])
        self.assertEqual([], self.publish(self.event(event_id="evt-002")))
        self.assertEqual(1, len(self.callback.received))

    def test_retry_keeps_event_identity_and_receiver_deduplicates(self):
        self.subscribe()
        self.assertEqual([204], self.publish(self.event()))
        self.assertEqual([204], self.publish(self.event(), SIGNING_TIME + 1))
        self.assertEqual(1, len(self.callback.received))

    def test_known_signature_and_modified_body(self):
        body, headers = signed_post("evt-001", self.event(), "test-subscription")
        self.assertEqual(EXPECTED_SIGNATURE, headers["webhook-signature"])
        self.assertEqual(401, self.post(body + b" ", headers)[0])
        self.assertEqual({}, self.callback.received)

    def test_failed_challenge_leaves_subscription_inactive(self):
        self.callback.verify = False
        self.assertEqual(-32015, self.subscribe()["error"]["code"])
        self.assertEqual(1, self.callback.challenges)
        self.assertEqual([], self.publish(self.event()))

    def test_different_callback_is_rejected_before_dialing(self):
        destination = dict(self.destination, url="https://example.invalid/callback")
        self.assertIn("error", self.subscribe(destination))
        self.assertEqual(0, self.callback.challenges)
        self.assertEqual([], self.publish(self.event()))


class PostCallbackTest(unittest.TestCase):
    def test_post_callback_sends_known_signed_bytes(self):
        host = "chatgpt.com"
        address = "8.8.8.8"  # Mocked public DNS answer. The test never connects to it.
        path = "/synthetic-callback?fixture=1"
        expected_body = (b'{"eventId":"evt-001","name":"spike.sample","timestamp":"2026-11-27T00:00:00Z",'
                         b'"data":{"sample":"alpha"},"cursor":null}')
        expected_headers = {
            "Content-Type": "application/json",
            "webhook-id": "evt-001",
            "webhook-timestamp": str(SIGNING_TIME),
            "webhook-signature": EXPECTED_SIGNATURE,
            "X-MCP-Subscription-Id": "test-subscription",
        }

        with (patch.object(tunnel.time, "time", return_value=SIGNING_TIME),
              patch.object(tunnel.socket, "getaddrinfo", return_value=[
                  (tunnel.socket.AF_INET, tunnel.socket.SOCK_STREAM, 0, "", (address, 443))
              ]),
              patch.object(tunnel.socket, "create_connection") as dial,
              patch.object(tunnel.ssl, "create_default_context") as tls,
              patch.object(tunnel.http.client, "HTTPSConnection") as https):
            connection = https.return_value
            connection.getresponse.return_value.status = 204

            self.assertEqual((204, b""), tunnel.post_callback(
                f"https://{host}{path}", KEY, "test-subscription", "evt-001", json.loads(expected_body)
            ))

            dial.assert_called_once_with((address, 443), timeout=5)
            tls.return_value.wrap_socket.assert_called_once_with(
                dial.return_value.__enter__.return_value, server_hostname=host
            )
            https.assert_called_once_with(host, timeout=5)
            connection.request.assert_called_once_with("POST", path, expected_body, expected_headers)
            connection.close.assert_called_once_with()


class TunnelServerProbe(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), tunnel.McpHandler)
        self.server.probe = tunnel.Probe()
        thread = Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(self.server.shutdown)
        self.enterContext(redirect_stdout(StringIO()))
        self.request = {"name": tunnel.EVENT, "arguments": {"sample": "work"},
                        "delivery": {"mode": "webhook", "url": "https://chatgpt.com/synthetic-callback"}}
        self.subscribe = self.subscription_request()

    def subscription_request(self, key=KEY, **params):
        delivery = dict(self.request["delivery"], secret="whsec_" + base64.b64encode(key).decode())
        return dict(self.request, delivery=delivery, **params)

    def echo_challenge(self, payload):
        return 200, json.dumps({"challenge": payload["challenge"]}).encode()

    def rpc(self, method, params=None):
        request = {"id": 1, "jsonrpc": "2.0", "method": method, "params": params or {}}
        with closing(http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)) as client:
            client.request("POST", "/mcp", json.dumps(request), {"Content-Type": "application/json"})
            response = client.getresponse()
            self.assertEqual(200, response.status)
            return json.loads(response.read())

    def test_http_discovery_subscription_and_removal(self):
        def webhook(url, key, sub_id, message_id, payload):
            if payload.get("type") == "verification":
                return self.echo_challenge(payload)
            self.assertEqual(message_id, payload["eventId"])
            self.assertEqual("work", payload["data"]["sample"])
            return 204, b""

        self.assertIn("events", self.rpc("initialize")["result"]["capabilities"])
        self.assertEqual(["2026-07-28"], self.rpc("server/discover")["result"]["supportedVersions"])
        self.assertEqual(tunnel.EVENT, self.rpc("events/list")["result"]["events"][0]["name"])
        self.assertEqual([], self.rpc("tools/list")["result"]["tools"])
        with patch.object(tunnel, "post_callback", side_effect=webhook) as callback:
            first = self.rpc("events/subscribe", self.subscribe)["result"]["id"]
            self.assertEqual(first, self.rpc("events/subscribe", self.subscribe)["result"]["id"])
            self.server.probe.emit("other")
            self.assertEqual(1, callback.call_count)
            self.server.probe.emit("work")
            self.assertEqual(2, callback.call_count)
            self.assertEqual({}, self.rpc("events/unsubscribe", self.request)["result"])
            self.server.probe.emit("work")
            self.assertEqual(2, callback.call_count)

    def test_unsubscribe_invalidates_refresh_while_verification_waits(self):
        replacement_key = b"r" * 32
        verifying = Event()
        release = Event()

        def webhook(url, key, sub_id, message_id, payload):
            if key == replacement_key:
                verifying.set()
                self.assertTrue(release.wait(5))
            return self.echo_challenge(payload)

        with (patch.object(tunnel, "post_callback", side_effect=webhook) as callback,
              ThreadPoolExecutor(max_workers=1) as requests):
            original = self.rpc("events/subscribe", self.subscribe)["result"]["id"]
            refresh = requests.submit(self.rpc, "events/subscribe", self.subscription_request(replacement_key))
            try:
                self.assertTrue(verifying.wait(2))
                self.assertEqual({}, self.rpc("events/unsubscribe", self.request)["result"])
                self.assertFalse(refresh.done())
            finally:
                release.set()

            self.assertEqual("subscription_superseded", refresh.result(timeout=2)["error"]["data"]["reason"])
            callback.reset_mock()
            self.server.probe.emit("work")
            callback.assert_not_called()
            fresh = self.rpc("events/subscribe", self.subscribe)["result"]["id"]
            self.assertNotEqual(original, fresh)

    def test_overlapping_identical_initial_requests_share_one_subscription(self):
        verifying = Event()
        duplicate_waiting = Event()
        release = Event()

        def webhook(url, key, sub_id, message_id, payload):
            verifying.set()
            self.assertTrue(release.wait(5))
            return self.echo_challenge(payload)

        read_result = self.server.probe.subscription_result

        def observe_wait(reservation):
            duplicate_waiting.set()
            return read_result(reservation)

        with (patch.object(tunnel, "post_callback", side_effect=webhook) as callback,
              patch.object(self.server.probe, "subscription_result", side_effect=observe_wait),
              ThreadPoolExecutor(max_workers=2) as requests):
            first = requests.submit(self.rpc, "events/subscribe", self.subscribe)
            try:
                self.assertTrue(verifying.wait(2))
                duplicate = requests.submit(self.rpc, "events/subscribe", self.subscribe)
                self.assertTrue(duplicate_waiting.wait(2))
                self.assertFalse(duplicate.done())
            finally:
                release.set()

            first_result = first.result(timeout=2)["result"]
            self.assertEqual(first_result, duplicate.result(timeout=2)["result"])
            self.assertEqual(1, len(self.server.probe.subscriptions))
            callback.assert_called_once()

    def test_stale_refresh_cannot_replace_newer_committed_key(self):
        stale_key = b"s" * 32
        newer_key = b"n" * 32
        verifying = Event()
        release = Event()

        def webhook(url, key, sub_id, message_id, payload):
            if payload.get("type") != "verification":
                return 204, b""
            if key == stale_key:
                verifying.set()
                self.assertTrue(release.wait(5))
            return self.echo_challenge(payload)

        with (patch.object(tunnel, "post_callback", side_effect=webhook) as callback,
              ThreadPoolExecutor(max_workers=1) as requests):
            original = self.rpc("events/subscribe", self.subscribe)["result"]["id"]
            stale = requests.submit(self.rpc, "events/subscribe", self.subscription_request(stale_key))
            try:
                self.assertTrue(verifying.wait(2))
                newer = self.rpc("events/subscribe", self.subscription_request(newer_key))["result"]["id"]
                self.assertEqual(original, newer)
            finally:
                release.set()

            self.assertEqual("subscription_superseded", stale.result(timeout=2)["error"]["data"]["reason"])
            callback.reset_mock()
            self.server.probe.emit("work")
            callback.assert_called_once()
            self.assertEqual((newer_key, original), callback.call_args.args[1:3])

    def test_http_ttl_defaults_and_caps_the_granted_expiry(self):
        cases = [({}, 1800), ({"ttlMs": None}, 1800), ({"ttlMs": 1250}, 1.25), ({"ttlMs": 10**400}, 1800)]

        def webhook(url, key, sub_id, message_id, payload):
            return self.echo_challenge(payload)

        with (patch.object(tunnel.time, "time", return_value=SIGNING_TIME),
              patch.object(tunnel, "post_callback", side_effect=webhook)):
            for params, granted_seconds in cases:
                with self.subTest(ttl=params):
                    result = self.rpc("events/subscribe", self.subscription_request(**params))["result"]
                    expires = datetime.fromtimestamp(SIGNING_TIME + granted_seconds, timezone.utc).isoformat()
                    self.assertEqual(expires, result["refreshBefore"])

    def test_unapproved_host_and_nonpublic_resolution_are_rejected(self):
        forged = dict(self.subscribe, delivery=dict(self.subscribe["delivery"], url="https://example.invalid/"))
        resolve = tunnel.socket.getaddrinfo

        def resolve_callback(host, *args, **kwargs):
            return resolve("127.0.0.1" if host == "chatgpt.com" else host, *args, **kwargs)

        with (patch.object(tunnel.socket, "getaddrinfo", side_effect=resolve_callback),
              patch.object(tunnel.socket, "create_connection", wraps=tunnel.socket.create_connection) as connect):
            self.assertEqual(-32602, self.rpc("events/subscribe", forged)["error"]["code"])
            self.assertEqual(-32015, self.rpc("events/subscribe", self.subscribe)["error"]["code"])
            self.assertTrue(all(call.args[0][1] == self.server.server_port for call in connect.call_args_list))


if __name__ == "__main__":
    unittest.main()
