"""The notes API over `http.server`: JSON in and out.

    GET    /notes[?q=words&tag=name]   the notes, by id
    POST   /notes                      {"title", "body", "tags"} → 201 and the note
    GET    /notes/<id>                 one note
    GET    /notes/<id>/html            one note as an HTML page (its Markdown body rendered)
    DELETE /notes/<id>                 204
"""

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from notes.render import note_html
from notes.search import search

_NOTE = re.compile(r"^/notes/(\d+)$")
_NOTE_HTML = re.compile(r"^/notes/(\d+)/html$")


class NotesHandler(BaseHTTPRequestHandler):
    store = None  # set by make_server

    def log_message(self, format, *args):  # noqa: A002 - the base class names it so
        pass

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/notes":
            query = parse_qs(url.query)
            found = search(self.store.all(), query.get("q", [""])[0], query.get("tag", [None])[0])
            return self._json(200, [n.to_json() for n in found])
        match = _NOTE.match(url.path)
        if match:
            note = self.store.get(int(match.group(1)))
            return self._json(200, note.to_json()) if note else self._json(404, {"error": "no such note"})
        match = _NOTE_HTML.match(url.path)
        if match:
            note = self.store.get(int(match.group(1)))
            return self._html(200, note_html(note)) if note else self._json(404, {"error": "no such note"})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/notes":
            return self._json(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            data = json.loads(self.rfile.read(length) or b"{}")
            note = self.store.add(data["title"], data.get("body", ""), data.get("tags", ()))
        except (KeyError, TypeError, ValueError) as error:
            return self._json(400, {"error": str(error) or "bad request"})
        self._json(201, note.to_json())

    def do_DELETE(self):
        match = _NOTE.match(urlsplit(self.path).path)
        if match and self.store.delete(int(match.group(1))):
            self.send_response(204)
            self.end_headers()
            return
        self._json(404, {"error": "no such note"})

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, status, page):
        body = page.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def make_server(store, host="127.0.0.1", port=8040):
    handler = type("BoundNotesHandler", (NotesHandler,), {"store": store})
    return ThreadingHTTPServer((host, port), handler)
