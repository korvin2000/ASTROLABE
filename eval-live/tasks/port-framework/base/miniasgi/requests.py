import json
from types import SimpleNamespace

from .datastructures import Headers, QueryParams


class Request:
    """A view of an ASGI http scope. ``json()`` raises the decoder's own ValueError for a body that is not JSON.
    ``state`` is shared by every Request made over the same scope, so middleware and endpoint see one object."""

    def __init__(self, scope, receive):
        self.scope = scope
        self.receive = receive
        self.method = scope["method"]
        self.path = scope["path"]
        self.headers = Headers(scope.get("headers", []))
        self.query_params = QueryParams(scope.get("query_string", b""))
        self.state = scope.setdefault("state", SimpleNamespace())

    @property
    def path_params(self):
        return self.scope.get("path_params", {})

    async def body(self):
        if "body" not in self.scope:
            chunks = []
            while True:
                message = await self.receive()
                chunks.append(message.get("body", b""))
                if not message.get("more_body", False):
                    break
            self.scope["body"] = b"".join(chunks)
        return self.scope["body"]

    async def json(self):
        return json.loads(await self.body())
