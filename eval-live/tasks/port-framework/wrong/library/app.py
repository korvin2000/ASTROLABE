import itertools

from miniasgi import App, BaseHTTPMiddleware, JSONResponse

from .auth import RequireToken
from .errors import ValidationError
from .store import BookStore
from .views import register


class RequestId(BaseHTTPMiddleware):
    """Echoes ``X-Request-Id`` (or numbers the request) on every answer, the ones of the other middleware included."""

    def __init__(self, app):
        super().__init__(app)
        self.counter = itertools.count(1)

    async def dispatch(self, request, call_next):
        request_id = request.headers.get("x-request-id") or "req-%d" % next(self.counter)
        response = await call_next(request)
        response.headers["x-request-id"] = request_id
        return response


def invalid(request, exc):
    return JSONResponse({"error": "validation", "fields": exc.fields}, 422)


def create_app(store=None, tokens=("dev-token",)):
    """The shelf service over ``store`` (a fresh one by default); writes need one of ``tokens``."""
    store = store if store is not None else BookStore()
    app = App()
    app.add_middleware(RequestId)
    app.add_middleware(RequireToken, tokens=tokens)
    app.add_exception_handler(ValidationError, invalid)
    register(app, store)
    return app
