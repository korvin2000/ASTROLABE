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


class ClearCompletedTest(unittest.TestCase):
    def setUp(self):
        self.port = free_port()
        self.server = subprocess.Popen([sys.executable, "server.py", "--port", str(self.port)], cwd=ROOT,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 15
        while True:
            try:
                self.call("GET", "/api/todos")
                return
            except OSError:
                if self.server.poll() is not None or time.monotonic() > deadline:
                    self.tearDown()
                    raise
                time.sleep(0.2)

    def tearDown(self):
        self.server.kill()
        self.server.wait(timeout=10)

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with OPENER.open(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def test_only_completed_todos_are_removed(self):
        ids = [self.call("POST", "/api/todos", {"title": name})[1]["id"] for name in ("a", "b", "c")]
        self.call("PATCH", f"/api/todos/{ids[0]}", {"done": True})
        self.call("PATCH", f"/api/todos/{ids[2]}", {"done": True})
        self.assertEqual(self.call("DELETE", "/api/todos?done=true"), (200, {"removed": 2}))
        self.assertEqual(self.call("GET", "/api/todos")[1], [{"id": ids[1], "title": "b", "done": False}])
        self.assertEqual(self.call("POST", "/api/todos", {"title": "d"})[1]["id"], ids[2] + 1)

    def test_nothing_completed_removes_nothing(self):
        self.call("POST", "/api/todos", {"title": "a"})
        self.assertEqual(self.call("DELETE", "/api/todos?done=true"), (200, {"removed": 0}))
        self.assertEqual(len(self.call("GET", "/api/todos")[1]), 1)

    def test_delete_without_done_true_is_refused(self):
        self.call("POST", "/api/todos", {"title": "a"})
        self.assertEqual(self.call("DELETE", "/api/todos")[0], 400)
        self.assertEqual(len(self.call("GET", "/api/todos")[1]), 1)


if __name__ == "__main__":
    unittest.main()
