"""Real HTTP tests using a deterministic backend; no containers required."""
import importlib.util
import json
import os
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Integration(unittest.TestCase):
    def test_gateway(self):
        servers = []
        class Backend(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        def start(handler):
            server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            servers.append(server)
            return f"http://127.0.0.1:{server.server_port}"
        def load(role):
            spec = importlib.util.spec_from_file_location(role, Path(__file__).with_name("server.py"))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.ROLE = role
            return module
        def request(url, method="GET", client=None):
            headers = {"X-IBM-Client-Id": client} if client else {}
            req = urllib.request.Request(url, data=b'{"test":true}' if method == "POST" else None,
                                         method=method, headers=headers)
            try:
                response = urllib.request.urlopen(req, timeout=5)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, response.read()
        try:
            backend = start(Backend)
            apic = load("apic")
            apic.LIMIT = 2
            control = start(apic.Handler)
            gateway = load("datapower")
            gateway.CONTROL, gateway.BACKEND = control, backend
            url = start(gateway.Handler)
            self.assertEqual(request(control + "/catalog")[0], 200)
            self.assertEqual(request(url + "/api/payments", "POST")[0], 401)
            self.assertEqual(request(url + "/api/payments")[0], 405)
            self.assertEqual(request(url + "/unknown", "POST", gateway.CLIENT_ID)[0], 404)
            for _ in range(2):
                self.assertEqual(request(url + "/api/payments", "POST", gateway.CLIENT_ID), (200, b'{"test":true}'))
            self.assertEqual(request(url + "/api/payments", "POST", gateway.CLIENT_ID)[0], 429)
            gateway.HITS.clear()
            gateway.BACKEND = "http://127.0.0.1:1"
            self.assertEqual(request(url + "/api/payments", "POST", gateway.CLIENT_ID)[0], 502)
            gateway.CONTROL = "http://127.0.0.1:1"
            self.assertEqual(request(url + "/api/payments", "POST", gateway.CLIENT_ID)[0], 503)
            metrics = request(url + "/metrics")[1].decode()
            self.assertIn('status="429"} 1', metrics)
            self.assertNotIn(gateway.CLIENT_ID, metrics)
            mule = load("mule")
            mule.BACKEND = backend
            mule_url = start(mule.Handler)
            self.assertEqual(request(mule_url + "/exchange")[0], 200)
            self.assertEqual(request(mule_url + "/runtime")[0], 200)
            gateway.CONTROL, gateway.BACKEND = control, mule_url
            gateway.HITS.clear()
            status, body = request(url + "/api/payments", "POST", gateway.CLIENT_ID)
            self.assertEqual(status, 200)
            result = json.loads(body)
            self.assertEqual(result["result"]["payload"], {"test": True})
            self.assertEqual(result["result"]["source"], "anypoint-simulator")
            self.assertEqual(result["result"]["operation"], "/api/payments")
            bad = urllib.request.Request(mule_url + "/api/payments", data=b'[]', method="POST")
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(bad)
            self.assertEqual(error.exception.code, 400)
            error.exception.close()
            mule.BACKEND = "http://127.0.0.1:1"
            self.assertEqual(request(mule_url + "/api/payments", "POST")[0], 502)
        finally:
            for server in servers:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
