import json
import os
import socket
import subprocess
import sys
import time
import unittest
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class HealthTest(unittest.TestCase):
    def setUp(self):
        self.port = free_port()
        self.server = subprocess.Popen([sys.executable, "server.py", "--port", str(self.port)], cwd=ROOT,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def tearDown(self):
        self.server.kill()
        self.server.wait(timeout=10)

    def test_health(self):
        deadline = time.monotonic() + 15
        while True:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/health", timeout=2) as response:
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.loads(response.read()), {"status": "ok"})
                    return
            except OSError:
                if self.server.poll() is not None or time.monotonic() > deadline:
                    raise
                time.sleep(0.2)


if __name__ == "__main__":
    unittest.main()
