"""Hidden external smoke test of rest-todo: starts `server.py` of a copy of the finished workspace and drives it over HTTP."""

import json
import os
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Smoke(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        cls.server = subprocess.Popen([sys.executable, "server.py", "--port", str(cls.port)], cwd=ROOT,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 20
        while True:
            try:
                cls.call("GET", "/health")
                return
            except OSError:
                if cls.server.poll() is not None or time.monotonic() > deadline:
                    cls.tearDownClass()
                    raise
                time.sleep(0.2)

    @classmethod
    def tearDownClass(cls):
        cls.server.kill()
        cls.server.wait(timeout=10)

    @classmethod
    def call(cls, method, path, body=None, raw=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(f"http://127.0.0.1:{cls.port}{path}", data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.headers.get("Content-Type", ""), response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.headers.get("Content-Type", ""), error.read()

    def json_call(self, method, path, status, body=None, raw=None):
        code, content_type, payload = self.call(method, path, body, raw)
        self.assertEqual(code, status, f"{method} {path}: {payload!r}")
        self.assertTrue(content_type.startswith("application/json"), f"{method} {path}: content type {content_type!r}")
        return json.loads(payload)

    def test_scenario(self):
        self.assertEqual(self.json_call("GET", "/health", 200), {"status": "ok"})
        self.assertEqual(self.json_call("GET", "/todos", 200), [])

        first = self.json_call("POST", "/todos", 201, {"title": "buy milk"})
        self.assertEqual(first, {"id": 1, "title": "buy milk", "done": False})
        second = self.json_call("POST", "/todos", 201, {"title": "write report"})
        self.assertEqual(second, {"id": 2, "title": "write report", "done": False})
        self.assertEqual(self.json_call("GET", "/todos", 200), [first, second])
        self.assertEqual(self.json_call("GET", "/todos/1", 200), first)
        self.assertIn("error", self.json_call("GET", "/todos/99", 404))

        done = self.json_call("PATCH", "/todos/1", 200, {"done": True})
        self.assertEqual(done, {"id": 1, "title": "buy milk", "done": True})
        renamed = self.json_call("PATCH", "/todos/1", 200, {"title": "buy oat milk"})
        self.assertEqual(renamed, {"id": 1, "title": "buy oat milk", "done": True})
        self.assertIn("error", self.json_call("PATCH", "/todos/1", 400, {"done": "yes"}))
        self.assertIn("error", self.json_call("PATCH", "/todos/1", 400, {"title": 5}))
        self.assertIn("error", self.json_call("PATCH", "/todos/99", 404, {"done": True}))

        self.assertIn("error", self.json_call("POST", "/todos", 400, {}))
        self.assertIn("error", self.json_call("POST", "/todos", 400, {"title": ""}))
        self.assertIn("error", self.json_call("POST", "/todos", 400, raw=b"{not json"))

        code, _, payload = self.call("DELETE", "/todos/2")
        self.assertEqual((code, payload), (204, b""))
        self.assertIn("error", self.json_call("DELETE", "/todos/2", 404))
        self.assertEqual(self.json_call("GET", "/todos", 200), [renamed])

        third = self.json_call("POST", "/todos", 201, {"title": "call mom"})
        self.assertEqual(third["id"], 3, "ids are never reused")
        self.assertIn("error", self.json_call("GET", "/nope", 404))


if __name__ == "__main__":
    unittest.main(verbosity=2)
