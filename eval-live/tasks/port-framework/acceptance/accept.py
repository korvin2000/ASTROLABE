"""Hidden acceptance of port-framework: run from the root of a copy of the finished workspace.

The service is driven through the bare ASGI protocol (no helper of the workspace), so what is judged is the wire
behaviour of ``library.app.create_app(...)`` and that the old layer is really gone.
"""

import ast
import asyncio
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

AUTH = {"Authorization": "Bearer dev-token"}


class Answer:
    def __init__(self, status, headers, body):
        self.status = status
        self.headers = {key.decode("latin-1").lower(): value.decode("latin-1") for key, value in headers}
        self.body = body

    def json(self):
        return json.loads(self.body.decode("utf-8"))


def drive(app, method, target, headers=None, body=b""):
    path, _, query = target.partition("?")
    raw = [(key.lower().encode("latin-1"), value.encode("latin-1")) for key, value in (headers or {}).items()]
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": method, "scheme": "http", "path": path,
        "raw_path": path.encode(), "query_string": query.encode(), "headers": raw, "server": ("testserver", 80), "client": ("127.0.0.1", 1234),
    }
    sent = []

    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        sent.append(message)

    asyncio.run(app(scope, receive, send))
    starts = [message for message in sent if message["type"] == "http.response.start"]
    assert len(starts) == 1, "%s %s: the app sent %d response starts" % (method, target, len(starts))
    data = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
    return Answer(starts[0]["status"], starts[0]["headers"], data)


def fresh(**options):
    from library.app import create_app
    from library.store import BookStore

    return create_app(BookStore(), **options)


class Client:
    def __init__(self, app):
        self.app = app

    def go(self, method, target, headers=None, json_body=None, raw=None):
        headers = dict(headers or {})
        body = raw if raw is not None else b""
        if json_body is not None:
            body = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        return drive(self.app, method, target, headers, body)

    def add(self, **fields):
        return self.go("POST", "/books", AUTH, fields)


def imported(path):
    """The module names a file imports, ``from http import server`` counting as ``http.server``."""
    names = []
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"), filename=str(path))):
        if isinstance(node, ast.Import):
            names += [alias.name for alias in node.names]
        elif isinstance(node, ast.ImportFrom) and node.level == 0:
            names.append(node.module or "")
            names += ["%s.%s" % (node.module, alias.name) for alias in node.names]
    return names


class LegacyIsGone(unittest.TestCase):
    def test_no_file_of_the_service_or_its_tests_imports_the_old_layer(self):
        offenders = []
        for folder in ("library", "tests"):
            for path in sorted((ROOT / folder).rglob("*.py")):
                for name in imported(path):
                    if name == "tinyhttp" or name.startswith("tinyhttp.") or name == "http.server":
                        offenders.append("%s imports %s" % (path.relative_to(ROOT).as_posix(), name))
        self.assertEqual(offenders, [])

    def test_the_service_runs_without_loading_the_old_layer(self):
        code = (
            "import sys, library.app, library.views, library.auth, library.__main__\n"
            "bad = [m for m in sys.modules if m == 'tinyhttp' or m.startswith('tinyhttp.') or m == 'http.server']\n"
            "print(bad)\nsys.exit(1 if bad else 0)\n"
        )
        done = subprocess.run([sys.executable, "-c", code], cwd=str(ROOT), capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_python_dash_m_library_starts_the_miniasgi_server(self):
        names = imported(ROOT / "library" / "__main__.py")
        self.assertTrue(any(name == "miniasgi" or name.startswith("miniasgi.") for name in names), names)


class Wire(unittest.TestCase):
    def setUp(self):
        self.c = Client(fresh())

    def test_health(self):
        answer = self.c.go("GET", "/health")
        self.assertEqual((answer.status, answer.json()), (200, {"status": "ok"}))
        self.assertTrue(answer.headers["content-type"].startswith("application/json"), answer.headers)
        self.assertRegex(answer.headers["x-request-id"], r"^req-\d+$")

    def test_request_ids_are_echoed_or_numbered_per_app(self):
        self.assertEqual(self.c.go("GET", "/health", {"X-Request-Id": "abc-1"}).headers["x-request-id"], "abc-1")
        first = self.c.go("GET", "/health").headers["x-request-id"]
        second = self.c.go("GET", "/health").headers["x-request-id"]
        self.assertNotEqual(first, second)
        self.assertEqual(Client(fresh()).go("GET", "/health").headers["x-request-id"], "req-1")
        self.assertEqual(Client(fresh()).go("GET", "/health").headers["x-request-id"], "req-1")

    def test_a_write_without_a_valid_token_is_401_with_an_id(self):
        for headers in ({}, {"Authorization": "Token dev-token"}, {"Authorization": "Bearer nope"}, {"Authorization": "bearer dev-token"}, {"Authorization": "Bearer"}):
            for method, target in (("POST", "/books"), ("PUT", "/books/1"), ("DELETE", "/books/1")):
                answer = self.c.go(method, target, headers, json_body={"title": "A", "author": "b"} if method != "DELETE" else None)
                label = "%s %s %r" % (method, target, headers)
                self.assertEqual((answer.status, answer.json()), (401, {"error": "unauthorized"}), label)
                self.assertEqual(answer.headers.get("www-authenticate"), "Bearer", label)
                self.assertRegex(answer.headers.get("x-request-id", ""), r"^req-\d+$", label + " carries a request id")

    def test_the_token_check_comes_before_routing_and_only_guards_writes(self):
        self.assertEqual(self.c.go("POST", "/nope").status, 401)
        self.assertEqual(self.c.go("POST", "/nope", AUTH).status, 404)
        self.assertEqual(self.c.go("GET", "/books").status, 200)
        # PATCH is not one of the guarded methods: it is the router that refuses it.
        self.assertEqual(self.c.go("PATCH", "/books/1").status, 405)

    def test_configured_tokens(self):
        client = Client(fresh(tokens=("a", "b")))
        body = {"title": "A", "author": "b"}
        self.assertEqual(client.go("POST", "/books", {"Authorization": "Bearer a"}, body).status, 201)
        self.assertEqual(client.go("POST", "/books", {"Authorization": "Bearer b"}, body).status, 201)
        self.assertEqual(client.go("POST", "/books", AUTH, body).status, 401)

    def test_create_then_read(self):
        created = self.c.add(title=" Dune ", author="Herbert", year=1965, tags=["SF", "sf", "Classic"])
        self.assertEqual(created.status, 201)
        self.assertEqual(created.headers["location"], "/books/1")
        expected = {"id": 1, "title": "Dune", "author": "Herbert", "year": 1965, "tags": ["classic", "sf"]}
        self.assertEqual(created.json(), expected)
        self.assertEqual(self.c.go("GET", "/books/1").json(), expected)
        self.assertEqual(self.c.add(title="Emma", author="Austen").headers["location"], "/books/2")

    def test_validation_errors(self):
        cases = [
            ({"author": "Herbert"}, {"title": "required"}),
            ({"title": "x", "author": " "}, {"author": "required"}),
            ({"title": "x", "author": "y", "year": "1965"}, {"year": "must be an integer between 1000 and 2100"}),
            ({"title": "x", "author": "y", "tags": "sf"}, {"tags": "must be a list of non-empty strings"}),
            ([], {"body": "must be an object"}),
        ]
        for body, fields in cases:
            answer = self.c.go("POST", "/books", AUTH, body)
            self.assertEqual((answer.status, answer.json()), (422, {"error": "validation", "fields": fields}), repr(body))
            self.assertTrue(answer.headers["x-request-id"])

    def test_a_body_that_is_not_json_is_400(self):
        for raw in (b"{nope", b"", b"\xff\xfe\x00"):
            for method, target in (("POST", "/books"), ("PUT", "/books/1")):
                self.c.add(title="A", author="b")
                answer = self.c.go(method, target, AUTH, raw=raw)
                self.assertEqual((answer.status, answer.json()), (400, {"error": "invalid json"}), "%s %r" % (method, raw))

    def test_unknown_paths_and_ids_are_404_json(self):
        for target in ("/books/9", "/books/abc", "/books/", "/shelves", "/"):
            answer = self.c.go("GET", target)
            self.assertEqual((answer.status, answer.json()), (404, {"error": "not found"}), target)
            self.assertRegex(answer.headers.get("x-request-id", ""), r"^req-\d+$", target)

    def test_a_method_the_path_does_not_take_is_405_with_allow(self):
        self.c.add(title="A", author="b")
        cases = [("PATCH", "/books/1", {"DELETE", "GET", "PUT"}), ("PUT", "/books", {"GET", "POST"}), ("DELETE", "/health", {"GET"})]
        for method, target, allowed in cases:
            answer = self.c.go(method, target, AUTH)
            self.assertEqual((answer.status, answer.json()), (405, {"error": "method not allowed"}), "%s %s" % (method, target))
            self.assertEqual({part.strip() for part in answer.headers["allow"].split(",")}, allowed, "%s %s" % (method, target))
            self.assertTrue(answer.headers["x-request-id"])

    def test_listing_filters_pages_and_counts(self):
        self.c.add(title="A", author="ann", tags=["x", "y"])
        self.c.add(title="B", author="ann", tags=["x"])
        self.c.add(title="C", author="bob", tags=["x", "y"])
        titles = lambda answer: [book["title"] for book in answer.json()["items"]]  # noqa: E731
        both = self.c.go("GET", "/books?tag=x&tag=y")
        self.assertEqual((titles(both), both.json()["total"], both.headers["x-total-count"]), (["A", "C"], 2, "2"))
        self.assertEqual(titles(self.c.go("GET", "/books?tag=X&tag=Y")), ["A", "C"])
        self.assertEqual(titles(self.c.go("GET", "/books?author=ann&tag=y")), ["A"])
        self.assertEqual(titles(self.c.go("GET", "/books?author=Ann")), [])
        paged = self.c.go("GET", "/books?limit=1&offset=1")
        self.assertEqual((titles(paged), paged.json()["total"], paged.headers["x-total-count"]), (["B"], 3, "3"))
        self.assertEqual(self.c.go("GET", "/books?limit=100").status, 200)
        for bad in ("limit=0", "limit=101", "limit=abc", "offset=-1", "offset=abc"):
            answer = self.c.go("GET", "/books?" + bad)
            self.assertEqual(answer.status, 400, bad)
            self.assertIsInstance(answer.json().get("error"), str, bad)

    def test_replace_and_delete(self):
        self.c.add(title="A", author="ann")
        missing = self.c.go("PUT", "/books/7", AUTH, raw=b"{nope")
        self.assertEqual((missing.status, missing.json()), (404, {"error": "not found"}))
        bad = self.c.go("PUT", "/books/1", AUTH, {"title": ""})
        self.assertEqual(bad.status, 422)
        replaced = self.c.go("PUT", "/books/1", AUTH, {"title": "B", "author": "bob", "year": 2001, "tags": ["Z"]})
        self.assertEqual((replaced.status, replaced.json()), (200, {"id": 1, "title": "B", "author": "bob", "year": 2001, "tags": ["z"]}))
        self.assertEqual(self.c.go("GET", "/books/1").json()["title"], "B")
        deleted = self.c.go("DELETE", "/books/1", AUTH)
        self.assertEqual((deleted.status, deleted.body), (204, b""))
        self.assertIn(deleted.headers.get("content-length", "0"), ("0",))
        self.assertEqual(self.c.go("DELETE", "/books/1", AUTH).status, 404)
        self.assertEqual(self.c.go("GET", "/books/1").status, 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)
