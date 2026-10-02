"""Todo app: the JSON API under /api/todos and the static page, on the Python standard library only.

`python server.py --port 8000` serves http://127.0.0.1:8000/ until it is stopped. Todos live in memory.
"""

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

STATIC = Path(__file__).resolve().parent / "static"
CONTENT_TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8"}


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.todos = []
        self.next_id = 1  # ids are never reused

    def all(self):
        with self.lock:
            return [dict(todo) for todo in self.todos]

    def add(self, title):
        with self.lock:
            todo = {"id": self.next_id, "title": title, "done": False}
            self.next_id += 1
            self.todos.append(todo)
            return dict(todo)

    def update(self, todo_id, changes):
        with self.lock:
            for todo in self.todos:
                if todo["id"] == todo_id:
                    todo.update(changes)
                    return dict(todo)
            return None


STORE = Store()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_static(self, name):
        path = STATIC / name
        if Path(name).name != name or path.suffix not in CONTENT_TYPES or not path.is_file():
            return self.send_json(404, {"error": "not found"})
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", CONTENT_TYPES[path.suffix])
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def read_object(self):
        """The JSON object in the request body, or None after a 400 was sent."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length))
        except ValueError:
            body = None
        if not isinstance(body, dict):
            self.send_json(400, {"error": "the body must be a JSON object"})
            return None
        return body

    def todo_id(self):
        parts = urlsplit(self.path).path.split("/")
        if len(parts) == 4 and parts[:3] == ["", "api", "todos"] and parts[3].isdigit():
            return int(parts[3])
        return None

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/api/todos":
            self.send_json(200, STORE.all())
        elif path in ("/", "/index.html"):
            self.send_static("index.html")
        elif path.startswith("/static/"):
            self.send_static(path[len("/static/"):])
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/todos":
            return self.send_json(404, {"error": "not found"})
        body = self.read_object()
        if body is None:
            return
        title = body.get("title")
        if not isinstance(title, str) or not title.strip():
            return self.send_json(400, {"error": "title must be a non-empty string"})
        self.send_json(201, STORE.add(title.strip()))

    def do_PATCH(self):
        todo_id = self.todo_id()
        if todo_id is None:
            return self.send_json(404, {"error": "not found"})
        body = self.read_object()
        if body is None:
            return
        changes = {}
        if "done" in body:
            if not isinstance(body["done"], bool):
                return self.send_json(400, {"error": "done must be a boolean"})
            changes["done"] = body["done"]
        if "title" in body:
            if not isinstance(body["title"], str) or not body["title"].strip():
                return self.send_json(400, {"error": "title must be a non-empty string"})
            changes["title"] = body["title"].strip()
        todo = STORE.update(todo_id, changes)
        if todo is None:
            return self.send_json(404, {"error": f"no todo {todo_id}"})
        self.send_json(200, todo)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
