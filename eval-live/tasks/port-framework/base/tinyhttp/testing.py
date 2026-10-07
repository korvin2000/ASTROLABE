import json as jsonlib
from urllib.parse import urlsplit

from .request import Request


class Result:
    def __init__(self, response):
        self.status = response.status
        self.headers = dict(response.headers)
        self.body = response.body

    def json(self):
        return jsonlib.loads(self.body.decode("utf-8"))


class Client:
    """Drives an App in-process: no socket, the same ``dispatch`` the server calls."""

    def __init__(self, app):
        self.app = app

    def request(self, method, url, json=None, data=None, headers=None):
        parts = urlsplit(url)
        body = data or b""
        headers = dict(headers or {})
        if json is not None:
            body = jsonlib.dumps(json).encode("utf-8")
            headers.setdefault("Content-Type", "application/json")
        return Result(self.app.dispatch(Request(method, parts.path, parts.query, headers, body)))

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, **kwargs):
        return self.request("POST", url, **kwargs)

    def put(self, url, **kwargs):
        return self.request("PUT", url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request("DELETE", url, **kwargs)
