"""Hidden acceptance of scenario-1004: run from the root of a copy of the finished workspace.

The vendored wheel is put first on `sys.path` (a pure-Python wheel imports as a zip), so the checks see marklet 1.2.0
from `vendor/` whatever is installed elsewhere.
"""

import hashlib
import html
import io
import os
import re
import sys
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WHEEL = os.path.join(ROOT, "vendor", "marklet-1.2.0-py3-none-any.whl")
WHEEL_SHA256 = "3774ab3539deb8439735af4aca2ae6d012f4472206481393025ca456ad2982ac"
sys.path.insert(0, ROOT)
sys.path.insert(0, WHEEL)

import marklet  # noqa: E402
from notes.api import make_server  # noqa: E402
from notes.store import NoteStore  # noqa: E402
from notesclient.cli import main  # noqa: E402
from notesclient.client import NotesClient, NotFound  # noqa: E402

PASTED = "Hi team,\n\n<p onclick=\"steal()\">Quarterly <b>numbers</b></p>\n<script>alert('x')</script>\n\n- see **row 3**\n- check `a<b`"
TITLE = "Q3 <draft> & \"notes\""


def page(title, body):
    return f"<article><h1>{html.escape(title)}</h1>{marklet.render(body, escape_html=True)}</article>"


class Server:
    def __enter__(self):
        self.store = NoteStore()
        self.server = make_server(self.store, port=0)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


class Dependency(unittest.TestCase):
    def test_vendor_untouched(self):
        with open(WHEEL, "rb") as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), WHEEL_SHA256, "vendor/ wheel changed")
        self.assertEqual(marklet.__version__, "1.2.0")

    def test_declared(self):
        with open(os.path.join(ROOT, "requirements.txt"), encoding="utf-8") as f:
            lines = [re.sub(r"\s+", "", line.split("#", 1)[0]) for line in f]
        self.assertIn("marklet==1.2.0", [line.lower() for line in lines if line], "requirements.txt declares marklet==1.2.0")


class HtmlView(unittest.TestCase):
    def test_page_renders_markdown(self):
        with Server() as s:
            s.store.add("Trip", "# Plan\n\nBook the *train* and **hotel**.\n\n- tickets\n- [map](https://example.org/map)")
            with urlopen(s.url + "/notes/1/html", timeout=10) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(response.headers.get("Content-Type", "").replace(" ", "").lower(), "text/html;charset=utf-8")
                body = response.read().decode("utf-8")
            self.assertEqual(body.strip(), page("Trip", "# Plan\n\nBook the *train* and **hotel**.\n\n- tickets\n- [map](https://example.org/map)"))
            self.assertIn("<em>train</em>", body)
            self.assertIn('<a href="https://example.org/map">map</a>', body)

    def test_raw_html_shows_as_text(self):
        with Server() as s:
            s.store.add(TITLE, PASTED)
            with urlopen(s.url + "/notes/1/html", timeout=10) as response:
                body = response.read().decode("utf-8")
            self.assertEqual(body.strip(), page(TITLE, PASTED))
            for markup in ("<script", "<b>", "<p onclick", "<draft>"):
                self.assertNotIn(markup, body)
            self.assertIn("&lt;script&gt;", body)
            self.assertIn("<strong>row 3</strong>", body)

    def test_unknown_note_is_404(self):
        with Server() as s:
            with self.assertRaises(HTTPError) as raised:
                urlopen(s.url + "/notes/7/html", timeout=10)
            self.assertEqual(raised.exception.code, 404)

    def test_json_view_unchanged(self):
        with Server() as s:
            s.store.add(TITLE, PASTED, ["mail"])
            client = NotesClient(s.url)
            self.assertEqual(client.get(1), {"id": 1, "title": TITLE, "body": PASTED, "tags": ["mail"]})


class Client(unittest.TestCase):
    def test_client_html(self):
        with Server() as s:
            s.store.add(TITLE, PASTED)
            client = NotesClient(s.url)
            self.assertEqual(client.html(1).strip(), page(TITLE, PASTED))
            with self.assertRaises(NotFound):
                client.html(5)

    def test_cli_show_html(self):
        with Server() as s:
            s.store.add("Trip", "Book the *train*.")
            out = io.StringIO()
            self.assertEqual(main(["--url", s.url, "show", "1", "--html"], out), 0)
            self.assertEqual(out.getvalue().strip(), page("Trip", "Book the *train*."))
            self.assertTrue(out.getvalue().endswith("\n"))
            out = io.StringIO()
            main(["--url", s.url, "show", "1"], out)
            self.assertEqual(out.getvalue(), "# Trip\n\nBook the *train*.\n")


if __name__ == "__main__":
    unittest.main(verbosity=1)
