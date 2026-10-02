import json
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        cls.server = subprocess.Popen([sys.executable, "server.py", "--port", str(cls.port)], cwd=ROOT,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 15
        while True:
            try:
                cls.call("GET", "/api/todos")
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
    def call(cls, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(f"http://127.0.0.1:{cls.port}{path}", data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with OPENER.open(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def test_create_list_and_update(self):
        status, first = self.call("POST", "/api/todos", {"title": "buy milk"})
        self.assertEqual((status, first["title"], first["done"]), (201, "buy milk", False))
        status, updated = self.call("PATCH", f"/api/todos/{first['id']}", {"done": True})
        self.assertEqual((status, updated["done"]), (200, True))
        status, listed = self.call("GET", "/api/todos")
        self.assertEqual(status, 200)
        self.assertIn(updated, listed)

    def test_unknown_todo_and_bad_input(self):
        self.assertEqual(self.call("PATCH", "/api/todos/9999", {"done": True})[0], 404)
        self.assertEqual(self.call("POST", "/api/todos", {"title": ""})[0], 400)
        self.assertEqual(self.call("PATCH", "/api/todos/1", {"done": "yes"})[0], 400)

    def test_page_is_served(self):
        with OPENER.open(f"http://127.0.0.1:{self.port}/", timeout=5) as response:
            self.assertEqual(response.status, 200)
            self.assertIn(b"todo-list", response.read())


if __name__ == "__main__":
    unittest.main()
