"""Hidden acceptance of ui-clear-done: run from the root of a copy of the finished workspace.

Part 1 starts `server.py` on a free port and drives the new `DELETE /api/todos?done=true` over HTTP: only completed
todos go, ids are not reused, a bare DELETE is refused. Part 2 reads what the server serves: the HTML is parsed with
`html.parser` (the button is there), `static/app.js` is checked as text (comments removed): there is no browser or
JavaScript engine here, so the page's behaviour is verified structurally - the button's click handler calls the new
operation, the page is redrawn afterwards, and the button's visibility follows the completed todos.
"""

import json
import re
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Page(HTMLParser):
    """The elements with an id: tag, attributes and the text inside."""

    def __init__(self):
        super().__init__()
        self.elements = []  # (id, tag, attributes, text parts)
        self._open = []  # (tag, element)

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        element = (attributes["id"], tag, attributes, []) if "id" in attributes else None
        if element:
            self.elements.append(element)
        if tag not in VOID:
            self._open.append((tag, element))

    def handle_endtag(self, tag):
        for index in range(len(self._open) - 1, -1, -1):
            if self._open[index][0] == tag:
                del self._open[index:]
                return

    def handle_data(self, data):
        for _, element in self._open:
            if element:
                element[3].append(data)

    def by_id(self, element_id):
        return [e for e in self.elements if e[0] == element_id]


def strip_js_comments(source):
    out, i, quote = [], 0, None
    while i < len(source):
        c = source[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < len(source):
                out.append(source[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
        elif c in "\"'`":
            quote = c
            out.append(c)
            i += 1
        elif source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


class Served(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        cls.server = subprocess.Popen([sys.executable, "server.py", "--port", str(cls.port)], cwd=ROOT,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 20
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
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()

    def api(self, method, path, status, body=None):
        code, payload = self.call(method, path, body)
        self.assertEqual(code, status, f"{method} {path}: {payload!r}")
        return json.loads(payload)

    def todos(self):
        return self.api("GET", "/api/todos", 200)

    def test_api_scenario(self):
        self.assertEqual(self.api("DELETE", "/api/todos?done=true", 200), {"removed": 0}, "nothing to clear yet")
        ids = [self.api("POST", "/api/todos", 201, {"title": name})["id"] for name in ("alpha", "bravo", "charlie")]
        self.api("PATCH", f"/api/todos/{ids[0]}", 200, {"done": True})
        self.api("PATCH", f"/api/todos/{ids[2]}", 200, {"done": True})

        self.assertEqual(self.api("DELETE", "/api/todos?done=true", 200), {"removed": 2})
        self.assertEqual(self.todos(), [{"id": ids[1], "title": "bravo", "done": False}], "only the completed todos go")
        self.assertEqual(self.api("DELETE", "/api/todos?done=true", 200), {"removed": 0})
        self.assertEqual(len(self.todos()), 1)

        for refused in ("/api/todos", "/api/todos?done=false", "/api/todos?done=yes"):
            self.api("DELETE", refused, 400)
        self.assertEqual(len(self.todos()), 1, "a refused DELETE removes nothing")

        fresh = self.api("POST", "/api/todos", 201, {"title": "delta"})
        self.assertEqual(fresh["id"], ids[2] + 1, "ids are never reused")
        self.assertEqual(self.api("PATCH", f"/api/todos/{fresh['id']}", 200, {"title": "delta two"})["title"], "delta two")
        self.assertEqual([t["title"] for t in self.todos()], ["bravo", "delta two"])

    def test_page_has_the_button(self):
        code, payload = self.call("GET", "/")
        self.assertEqual(code, 200)
        page = Page()
        page.feed(payload.decode("utf-8"))
        buttons = page.by_id("clear-completed")
        self.assertEqual(len(buttons), 1, "exactly one element with id=clear-completed")
        _, tag, _, text = buttons[0]
        self.assertEqual(tag, "button")
        self.assertIn("clear completed", " ".join("".join(text).split()).lower())
        self.assertEqual(len(page.by_id("todo-count")), 1, "the items-left counter is still on the page")

    def test_script_wires_the_button(self):
        code, payload = self.call("GET", "/static/app.js")
        self.assertEqual(code, 200)
        script = strip_js_comments(payload.decode("utf-8"))

        self.assertIn("clear-completed", script, "app.js never looks the button up")
        self.assertTrue(self.has_click_handler(script), "no click handler on the button")

        self.assertTrue(re.search(r"""["'`]DELETE["'`]""", script), "no DELETE request")
        call = re.search(r"/api/todos\?done=true", script)
        self.assertTrue(call, "app.js does not call /api/todos?done=true")
        redraw = r"\b(load|render|refresh|reload|update\w*|redraw|sync\w*)\b|location\.reload"
        self.assertTrue(re.search(redraw, script[call.end():call.end() + 500]),
                        "the page is not redrawn after the todos were cleared")

        self.assertTrue(re.search(r"\.hidden\s*=|(set|remove|toggle)Attribute\(\s*[\"']hidden|style\.display\s*=|classList\.(add|remove|toggle)", script),
                        "nothing shows or hides the button")
        self.assertIn("left", script, "the items-left counter was dropped")

    @staticmethod
    def has_click_handler(script):
        lookup = r"""document\.(?:getElementById\(\s*["']clear-completed["']\s*\)|querySelector\(\s*["']#clear-completed["']\s*\))"""
        click = r"""(?:addEventListener\(\s*["']click["']|onclick\s*=)"""
        for name in re.findall(r"(\w+)\s*=\s*" + lookup, script):
            if re.search(r"\b" + re.escape(name) + r"\s*\.\s*" + click, script):
                return True
        if re.search(lookup + r"\s*\.\s*" + click, script):
            return True
        # Delegation: one click listener that names the button.
        return bool(re.search(r"""["']click["']""", script) and re.search(r"(target|closest|matches)\b[^;\n]{0,80}clear-completed", script))


if __name__ == "__main__":
    unittest.main(verbosity=2)
