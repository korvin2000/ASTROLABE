"""In-memory todo REST API on the Python standard library: `python server.py --port 8080`."""

import argparse
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TODO_PATH = re.compile(r"^/todos/(\d+)$")


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.todos = {}
        self.next_id = 1

    def create(self, title):
        with self.lock:
            todo = {"id": self.next_id, "title": title, "done": False}
            self.todos[self.next_id] = todo
            self.next_id += 1
            return dict(todo)


class BadRequest(Exception):
    pass


class Handler(BaseHTTPRequestHandler):
    store = Store()

    def log_message(self, format, *args):
        pass

    def send_json(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            data = json.loads(self.rfile.read(length) or b"null")
        except ValueError:
            raise BadRequest("invalid JSON")
        if not isinstance(data, dict):
            raise BadRequest("the body must be a JSON object")
        return data

    def todo_id(self):
        match = TODO_PATH.match(self.path)
        return int(match.group(1)) if match else None

    def handle_request(self, method):
        try:
            if method == "GET" and self.path == "/health":
                return self.send_json(200, {"status": "ok"})
            if self.path == "/todos":
                if method == "GET":
                    with self.store.lock:
                        return self.send_json(200, [dict(t) for t in self.store.todos.values()])
                if method == "POST":
                    title = self.body().get("title")
                    if not isinstance(title, str) or not title:
                        raise BadRequest("title must be a non-empty string")
                    return self.send_json(201, self.store.create(title))
            todo_id = self.todo_id()
            if todo_id is not None and method in ("GET", "PATCH", "DELETE"):
                return self.on_todo(method, todo_id)
            self.send_json(404, {"error": f"no route for {method} {self.path}"})
        except BadRequest as error:
            self.send_json(400, {"error": str(error)})

    def on_todo(self, method, todo_id):
        changes = self.body() if method == "PATCH" else {}
        if "title" in changes and (not isinstance(changes["title"], str) or not changes["title"]):
            raise BadRequest("title must be a non-empty string")
        if "done" in changes and not isinstance(changes["done"], bool):
            raise BadRequest("done must be a boolean")
        with self.store.lock:
            todo = self.store.todos.get(todo_id)
            if todo is None:
                return self.send_json(404, {"error": f"no todo {todo_id}"})
            if method == "DELETE":
                del self.store.todos[todo_id]
                self.send_response(204)
                self.end_headers()
                return None
            for key in ("title", "done"):
                if key in changes:
                    todo[key] = changes[key]
            return self.send_json(200, dict(todo))

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_PATCH(self):
        self.handle_request("PATCH")

    def do_DELETE(self):
        self.handle_request("DELETE")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8080)
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
