"""A small HTTP/1.1 server for an ASGI app on asyncio streams: one request per connection, no keep-alive."""

import asyncio
from http import HTTPStatus
from urllib.parse import urlsplit

from .datastructures import Headers


async def _handle(app, reader, writer):
    try:
        head = (await reader.readuntil(b"\r\n\r\n")).decode("latin-1").split("\r\n")
        method, target, _version = head[0].split(" ", 2)
        fields = Headers([tuple(part.strip() for part in line.split(":", 1)) for line in head[1:] if ":" in line])
        length = int(fields.get("content-length", "0"))
        body = await reader.readexactly(length) if length else b""
        parts = urlsplit(target)
        scope = {"type": "http", "method": method, "path": parts.path, "query_string": parts.query.encode("latin-1"), "headers": fields.raw()}
        sent = []

        async def receive():
            return {"type": "http.request", "body": body, "more_body": False}

        async def send(message):
            sent.append(message)

        await app(scope, receive, send)
        start = next(message for message in sent if message["type"] == "http.response.start")
        lines = ["HTTP/1.1 %d %s" % (start["status"], HTTPStatus(start["status"]).phrase)]
        lines += ["%s: %s" % (key.decode("latin-1"), value.decode("latin-1")) for key, value in start["headers"]]
        lines.append("connection: close")
        payload = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
        writer.write("\r\n".join(lines).encode("latin-1") + b"\r\n\r\n" + payload)
        await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    finally:
        writer.close()


async def serve_async(app, host="127.0.0.1", port=8000):
    server = await asyncio.start_server(lambda reader, writer: _handle(app, reader, writer), host, port)
    async with server:
        await server.serve_forever()


def serve(app, host="127.0.0.1", port=8000):
    asyncio.run(serve_async(app, host, port))
