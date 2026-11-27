"""Disposable, single-operator MCP Events server for a private test tunnel."""

import argparse
import base64
from datetime import datetime, timezone
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
import re
import socket
import ssl
import sys
from threading import Lock, Thread
import time
from urllib.parse import urlsplit
from uuid import uuid4


EVENT = "spike.sample"
SAMPLES = ("work", "dot", "other")
VERSIONS = ("2025-03-26", "2025-06-18", "2025-11-25", "2026-07-28")
CAPABILITIES = {"events": {}, "tools": {}}
INFO = {"name": "issue-24-events-probe", "version": "0.1.0"}
SAMPLE_SCHEMA = {"type": "string", "enum": SAMPLES}
FILTER = {"type": "object", "properties": {"sample": SAMPLE_SCHEMA},
          "required": ["sample"], "additionalProperties": False}
DEFINITION = {
    "name": EVENT, "description": "A fixture-generated sample with no real PR data",
    "delivery": ["webhook"], "inputSchema": FILTER,
    "payloadSchema": {"type": "object", "additionalProperties": False,
                      "properties": {"sample": SAMPLE_SCHEMA, "sampleId": {"type": "string"}},
                      "required": ["sample", "sampleId"]},
}
MAX_BODY = 16_384
MAX_SUBSCRIPTIONS = 8
TTL_SECONDS = 1800


def evidence(action, **fields):
    print(json.dumps({"action": action, "at": datetime.now(timezone.utc).isoformat(), **fields}), flush=True)


def callback_target(url):
    target = urlsplit(url)
    host = target.hostname or ""
    if (len(url) > 2048 or not re.fullmatch(r"[\x21-\x7e]+", url) or "\\" in url
            or target.scheme != "https" or target.port not in (None, 443)
            or target.username is not None or target.password is not None or "#" in url
            or not re.fullmatch(r"(?:[a-z0-9-]+\.)*(?:chatgpt\.com|openai\.com)", host)):
        raise ValueError("callback_target")
    return target


def post_callback(url, key, sub_id, message_id, payload):
    target = callback_target(url)
    host = target.hostname
    addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    ips = [ipaddress.ip_address(entry[4][0]) for entry in addresses]
    if not ips or any(not ip.is_global or ip.is_multicast or ip.is_reserved or getattr(ip, "is_site_local", False)
                      for ip in ips):
        raise ValueError("callback_address")

    body = json.dumps(payload, separators=(",", ":")).encode()
    timestamp = str(int(time.time()))
    signature = base64.b64encode(hmac.digest(key, f"{message_id}.{timestamp}.".encode() + body, "sha256"))
    headers = {"Content-Type": "application/json", "webhook-id": message_id,
               "webhook-timestamp": timestamp, "webhook-signature": "v1," + signature.decode(),
               "X-MCP-Subscription-Id": sub_id}
    path = target.path or "/"
    if target.query:
        path += "?" + target.query

    # Dial a validated address once and verify TLS against the original hostname.
    address = next((ip for ip in ips if ip.version == 4), ips[0])
    connection = http.client.HTTPSConnection(host, timeout=5)
    try:
        with socket.create_connection((str(address), 443), timeout=5) as raw:
            connection.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
        connection.request("POST", path, body, headers)
        response = connection.getresponse()
        reply = response.read(4097) if payload.get("type") == "verification" else b""
        if len(reply) > 4096:
            raise ValueError("callback_response")
        return response.status, reply
    finally:
        connection.close()


class RpcError(Exception):
    def __init__(self, code, reason):
        self.code = code
        self.reason = reason


class Probe:
    def __init__(self):
        self.subscriptions = {}
        self.lock = Lock()

    def rpc(self, method, params):
        if method == "initialize":
            requested = params.get("protocolVersion")
            return {"protocolVersion": requested if requested in VERSIONS else "2025-11-25",
                    "serverInfo": INFO, "capabilities": CAPABILITIES}
        if method == "server/discover":
            return {"resultType": "complete", "supportedVersions": [VERSIONS[-1]],
                    "capabilities": CAPABILITIES, "serverInfo": INFO}
        if method == "events/list":
            return {"events": [DEFINITION]}
        if method == "tools/list":
            return {"tools": []}
        if method in ("ping", "notifications/initialized", "notifications/cancelled"):
            return {}
        if method not in ("events/subscribe", "events/unsubscribe"):
            raise RpcError(-32601, "method_not_found")

        destination = params["delivery"]
        arguments = params["arguments"]
        sample = arguments["sample"]
        if (params["name"] != EVENT or set(arguments) != {"sample"} or sample not in SAMPLES
                or destination["mode"] != "webhook"):
            raise ValueError("invalid_subscription")
        url = destination["url"]
        callback_target(url)
        identity = (sample, url)

        with self.lock:
            if method == "events/unsubscribe":
                removed = self.subscriptions.pop(identity, None)
                evidence("unsubscribe", sample=sample, matched=removed is not None,
                         subscription=removed[0] if removed else None)
                return {}

            requested_ttl = params.get("ttlMs")
            ttl = TTL_SECONDS
            if requested_ttl is not None:
                if type(requested_ttl) is not int or requested_ttl <= 0:
                    raise ValueError("invalid_ttl")
                ttl = min(ttl, requested_ttl / 1000)
            self.purge_expired()
            previous = self.subscriptions.get(identity)
            if previous is None and len(self.subscriptions) >= MAX_SUBSCRIPTIONS:
                raise RpcError(-32000, "subscription_limit")
            secret = destination["secret"]
            if not secret.startswith("whsec_"):
                raise ValueError("invalid_secret")
            key = base64.b64decode(secret[6:], validate=True)
            if not 24 <= len(key) <= 64:
                raise ValueError("invalid_secret")
            sub_id = previous[0] if previous else "sub_" + uuid4().hex
            if previous is None or not hmac.compare_digest(previous[1], key):
                self.verify(url, key, sub_id)
            expires = time.time() + ttl
            self.subscriptions[identity] = (sub_id, key, expires)
            evidence("subscribe", sample=sample, subscription=sub_id, refreshed=previous is not None)
            return {"id": sub_id, "refreshBefore": datetime.fromtimestamp(expires, timezone.utc).isoformat(),
                    "cursor": None, "truncated": False}

    def purge_expired(self):
        now = time.time()
        self.subscriptions = {identity: sub for identity, sub in self.subscriptions.items() if sub[2] > now}

    def verify(self, url, key, sub_id):
        challenge = uuid4().hex
        try:
            status, reply = post_callback(url, key, sub_id, "verification_" + uuid4().hex,
                                          {"type": "verification", "challenge": challenge})
            echo = json.loads(reply)["challenge"] if 200 <= status < 300 else ""
            if isinstance(echo, str) and hmac.compare_digest(echo.encode(), challenge.encode()):
                evidence("callback_verified", subscription=sub_id, status=status)
                return
            evidence("callback_rejected", subscription=sub_id, status=status)
        except TimeoutError:
            raise RpcError(-32015, "timeout") from None
        except (OSError, ValueError, TypeError, KeyError, http.client.HTTPException):
            pass
        raise RpcError(-32015, "challenge_failed")

    def emit(self, sample):
        event_id = "evt_" + uuid4().hex
        sample_id = "sample_" + uuid4().hex[:12]
        event = {"eventId": event_id, "name": EVENT, "timestamp": datetime.now(timezone.utc).isoformat(),
                 "data": {"sample": sample, "sampleId": sample_id}, "cursor": None}
        with self.lock:
            self.purge_expired()
            matches = [(url, sub) for (filter_sample, url), sub in self.subscriptions.items()
                       if filter_sample == sample]
            evidence("emit", sample=sample, sample_id=sample_id, event=event_id, targets=len(matches))
            for url, (sub_id, key, expires) in matches:
                if expires <= time.time():
                    continue
                try:
                    status, _ = post_callback(url, key, sub_id, event_id, event)
                    evidence("delivery", subscription=sub_id, event=event_id, status=status)
                except (OSError, ValueError, http.client.HTTPException):
                    evidence("delivery_failed", subscription=sub_id, event=event_id)


class McpHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def setup(self):
        self.request.settimeout(8)
        super().setup()

    def respond(self, status, message=None):
        payload = json.dumps(message).encode() if message is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.respond(405 if self.path == "/mcp" else 404)

    def do_POST(self):
        if self.path != "/mcp":
            self.respond(404)
            return
        if self.headers.get("Origin") is not None:
            self.respond(403)
            return
        request_id = None
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                self.respond(413)
                return
            message = json.loads(self.rfile.read(length))
            request_id = message.get("id")
            method = message["method"]
            params = message.get("params", {})
            if message["jsonrpc"] != "2.0" or not isinstance(params, dict):
                raise ValueError("invalid_request")
            if "id" not in message:
                self.respond(202)
                return
            result = self.server.probe.rpc(method, params)
            evidence("rpc", method=method, outcome="ok")
            self.respond(200, {"jsonrpc": "2.0", "id": request_id, "result": result})
            return
        except RpcError as error:
            code, reason = error.code, error.reason
        except (AttributeError, KeyError, TypeError, ValueError):
            code, reason = -32602, "invalid_request"
        evidence("rpc_error", code=code, reason=reason)
        message = "CallbackEndpointError" if code == -32015 else reason
        self.respond(200, {"jsonrpc": "2.0", "id": request_id,
                           "error": {"code": code, "message": message, "data": {"reason": reason}}})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0, help="Loopback port (0 selects an ephemeral port)")
    args = parser.parse_args()
    with HTTPServer(("127.0.0.1", args.port), McpHandler) as server:
        server.probe = Probe()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        evidence("listening", port=server.server_port)
        try:
            for line in sys.stdin:
                sample = line.strip()
                if sample == "quit":
                    break
                if sample in SAMPLES:
                    server.probe.emit(sample)
        finally:
            server.shutdown()
            worker.join()


if __name__ == "__main__":
    main()
