"""Educational API Connect control plane and DataPower gateway; no IBM code."""
import collections
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROLE = os.getenv("ROLE", "datapower")
CONTROL = os.getenv("CONTROL_URL", "http://apic-simulator:8080")
BACKEND = os.getenv("BACKEND_URL", "http://host.docker.internal:18080")
CLIENT_ID = os.getenv("CLIENT_ID", "demo-client")
LIMIT = int(os.getenv("RATE_LIMIT", "10"))
WINDOW = int(os.getenv("RATE_WINDOW_SECONDS", "60"))
if LIMIT < 1 or WINDOW < 1:
    raise ValueError("Rate limit and window must be positive")
LOCK = threading.Lock()
COUNTS = collections.Counter()
DURATION = collections.Counter()
HITS = collections.deque()
ROUTES = ["/api/payments", "/api/cards/authorize", "/api/payments/{id}/refund"]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), type(
    "NoRedirect", (urllib.request.HTTPRedirectHandler,),
    {"redirect_request": lambda *args, **kwargs: None})())


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body, content_type="application/json", extra=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (extra or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_PUT(self):
        self.handle_request()

    def do_DELETE(self):
        self.handle_request()

    def do_PATCH(self):
        self.handle_request()

    def handle_request(self):
        path = self.path.split("?", 1)[0]
        if self.command == "GET" and path == "/health":
            return self.reply(200, {"status": "UP", "role": ROLE, "simulation": True})
        if self.command == "GET" and path == "/metrics":
            lines = ["# TYPE ibm_simulator_up gauge", f'ibm_simulator_up{{component="{ROLE}"}} 1',
                     "# TYPE ibm_simulator_requests_total counter",
                     "# TYPE ibm_simulator_request_duration_seconds_sum counter",
                     "# TYPE ibm_simulator_request_duration_seconds_count counter"]
            with LOCK:
                for (route, status), count in sorted(COUNTS.items()):
                    labels = f'component="{ROLE}",route="{route}",status="{status}"'
                    lines.append(f"ibm_simulator_requests_total{{{labels}}} {count}")
                    lines.append(f"ibm_simulator_request_duration_seconds_sum{{{labels}}} {DURATION[route, status]}")
                    lines.append(f"ibm_simulator_request_duration_seconds_count{{{labels}}} {count}")
            return self.reply(200, ("\n".join(lines) + "\n").encode(), "text/plain; version=0.0.4")
        started = time.monotonic()
        route = path if path in ROUTES else (
            ROUTES[2] if re.fullmatch(r"/api/payments/[A-Za-z0-9_-]+/refund", path) else "other")
        status = 500
        incoming_id = self.headers.get("X-Request-ID", "")
        request_id = incoming_id if ROLE == "mule" and re.fullmatch(r"[a-f0-9-]{36}", incoming_id) else str(uuid.uuid4())
        headers = {"X-Request-ID": request_id, "X-Simulation": "true"}
        try:
            if ROLE == "mule":
                status, body = self.mule_flow(path, route, request_id)
                headers["X-Integration"] = "mulesoft-anypoint-simulator"
            elif ROLE == "apic":
                route = path if path in ("/catalog", "/policy") else "other"
                if self.command != "GET":
                    status, body = 405, {"error": "read_only_simulated_control_plane"}
                elif path in ("/catalog", "/policy"):
                    status, body = 200, {"product": "checkout", "version": "1.0.0", "catalog": "sandbox",
                                         "state": "published", "plan": "starter", "routes": ROUTES,
                                         "rate_limit": LIMIT, "window_seconds": WINDOW, "simulation": True}
                else:
                    status, body = 404, {"error": "not_found"}
            elif route == "other":
                status, body = 404, {"error": "api_not_published"}
            elif self.command != "POST":
                status, body = 405, {"error": "method_not_allowed"}
                headers["Allow"] = "POST"
            elif self.headers.get("X-IBM-Client-Id") != CLIENT_ID:
                status, body = 401, {"error": "invalid_client_id"}
            else:
                try:
                    with OPENER.open(CONTROL + "/policy", timeout=2) as response:
                        policy = json.load(response)
                    limit, window = int(policy["rate_limit"]), int(policy["window_seconds"])
                    if limit < 1 or window < 1 or route not in policy["routes"]:
                        raise ValueError("Invalid policy")
                except (OSError, ValueError, KeyError, TypeError):
                    status, body = 503, {"error": "control_plane_unavailable"}
                else:
                    with LOCK:
                        now = time.monotonic()
                        while HITS and HITS[0] <= now - window:
                            HITS.popleft()
                        allowed = len(HITS) < limit
                        if allowed:
                            HITS.append(now)
                    if not allowed:
                        status, body = 429, {"error": "rate_limit_exceeded"}
                        headers["Retry-After"] = window
                    else:
                        try:
                            length = int(self.headers.get("Content-Length", "0"))
                        except ValueError:
                            length = -1
                        if self.headers.get("Transfer-Encoding") or length < 0 or length > 1048576:
                            status, body = 413, {"error": "invalid_or_oversized_body"}
                        else:
                            self.connection.settimeout(5)
                            payload = self.rfile.read(length)
                            request = urllib.request.Request(BACKEND + path, data=payload, method="POST",
                                headers={"Content-Type": self.headers.get("Content-Type", "application/json"),
                                         "X-Request-ID": request_id, "X-Gateway": "datapower-simulator"})
                            try:
                                with OPENER.open(request, timeout=3) as response:
                                    body = response.read(1048577)
                                    status = response.status
                                    headers["X-Gateway"] = "datapower-simulator"
                                    if response.headers.get("X-Integration"):
                                        headers["X-Integration"] = response.headers["X-Integration"]
                            except urllib.error.HTTPError as error:
                                status, body = error.code, error.read(1048577)
                            except TimeoutError:
                                status, body = 504, {"error": "backend_timeout"}
                            except OSError:
                                status, body = 502, {"error": "backend_unavailable"}
            self.reply(status, body, extra=headers)
        finally:
            elapsed = time.monotonic() - started
            with LOCK:
                COUNTS[route, status] += 1
                DURATION[route, status] += elapsed
            print(json.dumps({"component": ROLE, "route": route, "status": status,
                              "request_id": request_id, "duration_seconds": elapsed}), flush=True)

    def mule_flow(self, path, route, request_id):
        if self.command == "GET" and path == "/exchange":
            return 200, {"simulation": True, "assets": [{"name": "checkout-integration",
                "version": "1.0.0", "routes": ROUTES,
                "steps": ["http-listener", "validate-json", "transform", "http-request", "transform-response"]}]}
        if self.command == "GET" and path == "/runtime":
            return 200, {"simulation": True, "application": "checkout-integration", "status": "RUNNING"}
        if route == "other":
            return 404, {"error": "flow_not_found"}
        if self.command != "POST":
            return 405, {"error": "method_not_allowed"}
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if self.headers.get("Transfer-Encoding") or not 0 <= length <= 1048576:
            return 413, {"error": "invalid_or_oversized_body"}
        self.connection.settimeout(5)
        try:
            value = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(value, dict):
                raise ValueError("JSON object required")
            # Illustrative mapping only; no DataWeave engine or real payment handling.
            mapped = {"operation": route, "payload": value, "source": "anypoint-simulator"}
        except (ValueError, UnicodeDecodeError):
            return 400, {"error": "invalid_json_object"}
        request = urllib.request.Request(BACKEND + path, data=json.dumps(mapped).encode(), method="POST",
            headers={"Content-Type": "application/json", "X-Request-ID": request_id})
        try:
            try:
                response = OPENER.open(request, timeout=2)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                data = response.read(1048577)
                if len(data) > 1048576:
                    return 502, {"error": "backend_response_too_large"}
                result = json.loads(data)
                return response.status, {"simulation": True, "integration": "checkout-integration",
                    "request_id": request_id, "backend_status": response.status, "result": result}
        except TimeoutError:
            return 504, {"error": "connector_timeout"}
        except OSError:
            return 502, {"error": "connector_unavailable"}
        except (ValueError, UnicodeDecodeError):
            return 502, {"error": "invalid_backend_json"}


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "8080"))), Handler).serve_forever()
