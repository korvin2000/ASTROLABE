from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .request import Request


def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        def _serve(self):
            parts = urlsplit(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            request = Request(self.command, parts.path, parts.query, dict(self.headers.items()), body)
            response = app.dispatch(request)
            self.send_response(response.status)
            for key, value in response.headers.items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(response.body)))
            self.end_headers()
            self.wfile.write(response.body)

        do_GET = do_POST = do_PUT = do_DELETE = _serve

        def log_message(self, *args):
            pass

    return Handler


def serve(app, host="127.0.0.1", port=8000):
    ThreadingHTTPServer((host, port), make_handler(app)).serve_forever()
