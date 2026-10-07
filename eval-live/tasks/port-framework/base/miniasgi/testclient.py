import asyncio
import json as jsonlib
from urllib.parse import urlsplit

from .datastructures import Headers


class TestResponse:
    def __init__(self, status_code, headers, content):
        self.status_code = status_code
        self.headers = headers
        self.content = content

    @property
    def text(self):
        return self.content.decode("utf-8")

    def json(self):
        return jsonlib.loads(self.content)


class TestClient:
    """Drives an ASGI app in-process, one request per call: no socket and no server."""

    __test__ = False  # not a test case, whatever the name says

    def __init__(self, app):
        self.app = app

    def request(self, method, url, *, json=None, content=b"", headers=None):
        parts = urlsplit(url)
        fields = Headers(headers)
        body = content.encode("utf-8") if isinstance(content, str) else content
        if json is not None:
            body = jsonlib.dumps(json).encode("utf-8")
            if "content-type" not in fields:
                fields["content-type"] = "application/json"
        scope = {
            "type": "http",
            "method": method.upper(),
            "path": parts.path,
            "query_string": parts.query.encode("latin-1"),
            "headers": fields.raw(),
        }
        return asyncio.run(self._call(scope, body))

    async def _call(self, scope, body):
        sent = []

        async def receive():
            return {"type": "http.request", "body": body, "more_body": False}

        async def send(message):
            sent.append(message)

        await self.app(scope, receive, send)
        start = next(message for message in sent if message["type"] == "http.response.start")
        data = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
        return TestResponse(start["status"], Headers(start["headers"]), data)

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, **kwargs):
        return self.request("POST", url, **kwargs)

    def put(self, url, **kwargs):
        return self.request("PUT", url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request("DELETE", url, **kwargs)
