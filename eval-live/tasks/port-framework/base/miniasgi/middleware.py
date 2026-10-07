import inspect

from .datastructures import Headers
from .exceptions import HTTPException
from .requests import Request
from .responses import JSONResponse, Response


async def _answer(handler, request, exc):
    result = handler(request, exc)
    return await result if inspect.isawaitable(result) else result


def _http_exception(request, exc):
    return JSONResponse({"detail": exc.detail}, exc.status_code, exc.headers)


class ExceptionMiddleware:
    """The innermost layer: it turns what the router and the endpoints raise into responses, through the handler
    registered for the exception's class (nearest base class first), else the default for an HTTPException.
    The middleware an app adds sit outside this layer: what they raise is not handled here."""

    def __init__(self, app, handlers):
        self.app = app
        self.handlers = handlers

    async def __call__(self, scope, receive, send):
        try:
            await self.app(scope, receive, send)
        except Exception as exc:  # noqa: BLE001
            handler = next((self.handlers[kind] for kind in type(exc).__mro__ if kind in self.handlers and kind is not Exception), None)
            if handler is None and isinstance(exc, HTTPException):
                handler = _http_exception
            if handler is None:
                raise
            response = await _answer(handler, Request(scope, receive), exc)
            await response(scope, receive, send)


class ServerErrorMiddleware:
    """The outermost layer: an exception nothing handled becomes the handler registered for ``Exception``, else a
    plain 500. Nothing the inner layers add to answers (headers set by middleware) reaches this response."""

    def __init__(self, app, handler=None):
        self.app = app
        self.handler = handler

    async def __call__(self, scope, receive, send):
        started = False

        async def guarded(message):
            nonlocal started
            started = started or message["type"] == "http.response.start"
            await send(message)

        try:
            await self.app(scope, receive, guarded)
        except Exception as exc:  # noqa: BLE001
            if started:
                raise
            if self.handler is None:
                response = Response("Internal Server Error", 500, media_type="text/plain")
            else:
                response = await _answer(self.handler, Request(scope, receive), exc)
            await response(scope, receive, send)


class BaseHTTPMiddleware:
    """Subclass and write ``async def dispatch(self, request, call_next)``: ``await call_next(request)`` runs the rest
    of the app and returns its Response, which dispatch may change or replace; dispatch returns the Response to send."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        request = Request(scope, receive)

        async def call_next(request):
            status, headers, chunks = 500, [], []

            async def capture(message):
                nonlocal status, headers
                if message["type"] == "http.response.start":
                    status, headers = message["status"], message["headers"]
                else:
                    chunks.append(message.get("body", b""))

            await self.app(request.scope, request.receive, capture)
            return Response(b"".join(chunks), status, Headers(headers))

        response = await self.dispatch(request, call_next)
        await response(scope, receive, send)

    async def dispatch(self, request, call_next):
        raise NotImplementedError
