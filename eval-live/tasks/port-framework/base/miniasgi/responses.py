import json

from .datastructures import Headers


class Response:
    """An answer to send: ``content`` (text or bytes), ``status_code``, ``headers`` (a dict or ``Headers``)."""

    media_type = None

    def __init__(self, content=b"", status_code=200, headers=None, media_type=None):
        self.body = content.encode("utf-8") if isinstance(content, str) else bytes(content)
        self.status_code = status_code
        self.headers = Headers(headers)
        media_type = media_type or self.media_type
        if media_type is not None and "content-type" not in self.headers:
            self.headers["content-type"] = media_type

    async def __call__(self, scope, receive, send):
        headers = Headers(self.headers.items())
        headers["content-length"] = str(len(self.body))
        await send({"type": "http.response.start", "status": self.status_code, "headers": headers.raw()})
        await send({"type": "http.response.body", "body": self.body})


class JSONResponse(Response):
    media_type = "application/json"

    def __init__(self, content, status_code=200, headers=None):
        super().__init__(json.dumps(content, separators=(",", ":")), status_code, headers)
