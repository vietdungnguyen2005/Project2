"""Local-only fault bridge: delay delivery after real Java commit, never log bearer values."""
import hashlib
import http.client
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

observed = {"forwarded": 0}
guard = threading.Lock()


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


class Bridge(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Request headers, bodies, keys and capabilities must not reach artifacts.

    def do_GET(self):
        self.handle_proxy()

    def do_POST(self):
        self.handle_proxy()

    def handle_proxy(self):
        if self.path == "/__qa/observation" and self.command == "GET":
            with guard:
                body = json.dumps(observed).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        target = urlsplit(os.environ["TEST_JAVA_ORIGIN"])
        assert target.hostname == "127.0.0.1" and target.port == 18082, "Only disposable QA Java is allowed"
        length = int(self.headers.get("Content-Length", "0"))
        assert 0 <= length <= 5_000_000
        body = self.rfile.read(length) if length else None
        headers = {key: value for key, value in self.headers.items() if key.lower() not in ("host", "connection", "transfer-encoding")}
        connection = http.client.HTTPConnection(target.hostname, target.port, timeout=20)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            upstream = connection.getresponse()
            payload = upstream.read()
            response_headers = upstream.getheaders()
        finally:
            connection.close()
        delay = self.command == "POST" and self.path == "/api/orders"
        if delay:
            assert upstream.status == 201, "Real Java checkout did not commit"
            order = json.loads(payload)
            with guard:
                observed.update(forwarded=observed["forwarded"] + 1,
                                orderNumber=order["orderNumber"], tokenDigest=digest(order["trackingToken"]))
            # Real commit already completed; delay only response delivery beyond BFF's 12s deadline.
            time.sleep(14)
        try:
            self.send_response(upstream.status)
            for key, value in response_headers:
                if key.lower() not in ("content-length", "transfer-encoding", "connection"):
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # Expected when BFF has aborted its upstream request.


def verify():
    base = os.environ["BASE_URL"]
    assert base == "http://127.0.0.1:13002", "Only the runner-owned frontend is allowed"
    headers = {"X-Checkout-Session": os.environ["TEST_CHECKOUT_CAPABILITY"],
               "Idempotency-Key": os.environ["TEST_CHECKOUT_KEY"] + "-bff-deadline"}
    payload = {"customer": {"name": "QA Deadline Buyer", "email": "deadline@example.test", "postalCode": "100-0001",
                            "prefecture": "Tokyo", "city": "Chiyoda", "addressLine": "1-1",
                            "paymentMethod": "COD", "privacyAccepted": True}, "lines": [{"sku": "VM-004", "quantity": 1}]}
    with sync_playwright() as playwright:
        request = playwright.request.new_context(timeout=25000)
        try:
            started = time.monotonic()
            timed_out = request.post(f"{base}/api/orders", headers=headers, data=payload)
            elapsed = time.monotonic() - started
            assert timed_out.status == 504 and timed_out.json()["code"] == "BACKEND_TIMEOUT"
            assert 10 <= elapsed < 20, "BFF did not return within the expected local deadline window"
            assert timed_out.headers.get("cache-control") == "no-store"
            recovered = request.get(f"{base}/api/orders/checkout", headers=headers)
            assert recovered.status == 200
            observation = request.get("http://127.0.0.1:18083/__qa/observation").json()
            order = recovered.json()
            assert observation["forwarded"] == 1
            assert order["orderNumber"] == observation["orderNumber"]
            assert digest(order["trackingToken"]) == observation["tokenDigest"]
            container = os.environ["TEST_POSTGRES_CONTAINER"]
            assert container.startswith("vmarket-auth-pg-")
            sql = f"SELECT (SELECT COUNT(*) FROM customer_order WHERE order_number='{order['orderNumber']}'), (SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{order['orderNumber']}' AND movement_type='ORDER_PLACED');"
            counts = subprocess.run(["docker", "exec", container, "psql", "-U", "vmarket", "-d", "vmarket", "-At", "-c", sql],
                                    check=True, capture_output=True, text=True).stdout.strip()
            assert counts == "1|1", "Timeout recovery duplicated order or stock movement"
            print(f"PASS: real Java commit -> delayed HTTP delivery -> BFF 504 ({elapsed:.2f}s) -> scoped GET recovers original token; one POST/order/movement")
        finally:
            request.dispose()


if __name__ == "__main__":
    if sys.argv[1] == "serve":
        ThreadingHTTPServer(("127.0.0.1", 18083), Bridge).serve_forever()
    elif sys.argv[1] == "verify":
        verify()
    else:
        raise SystemExit("Expected serve or verify")
