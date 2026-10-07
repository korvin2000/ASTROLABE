from .middleware import ExceptionMiddleware, ServerErrorMiddleware
from .routing import Router


class App:
    """An ASGI application: ``await app(scope, receive, send)``. The stack, outermost first: the server-error layer,
    the middleware (the one added last is the outermost of them), the exception layer, the router."""

    def __init__(self):
        self.router = Router()
        self.exception_handlers = {}
        self.user_middleware = []
        self._stack = None

    def route(self, path, methods=("GET",)):
        def register(endpoint):
            self.router.add_route(path, endpoint, methods)
            return endpoint

        return register

    def get(self, path):
        return self.route(path, ["GET"])

    def post(self, path):
        return self.route(path, ["POST"])

    def put(self, path):
        return self.route(path, ["PUT"])

    def delete(self, path):
        return self.route(path, ["DELETE"])

    def add_middleware(self, middleware_class, **options):
        """``middleware_class(app, **options)`` wraps everything added before it."""
        self._stack = None
        self.user_middleware.insert(0, (middleware_class, options))

    def add_exception_handler(self, exception_class, handler):
        """``handler(request, exc)`` (plain or ``async``) returns the Response; one for ``Exception`` itself only
        answers what nothing else handled."""
        self._stack = None
        self.exception_handlers[exception_class] = handler

    def _build(self):
        app = ExceptionMiddleware(self.router, self.exception_handlers)
        for middleware_class, options in reversed(self.user_middleware):
            app = middleware_class(app, **options)
        return ServerErrorMiddleware(app, self.exception_handlers.get(Exception))

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return
        if self._stack is None:
            self._stack = self._build()
        await self._stack(scope, receive, send)
