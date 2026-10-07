from .errors import HTTPError
from .response import json_response
from .routing import Route


class App:
    """Routes, hooks and error handlers. ``dispatch`` is synchronous and never raises."""

    def __init__(self):
        self.routes = []
        self._before = []
        self._after = []
        self._handlers = {}

    def route(self, method, pattern):
        def register(handler):
            self.routes.append(Route(method.upper(), pattern, handler))
            return handler

        return register

    def before_request(self, hook):
        """``hook(request)`` runs, in registration order, before the request is routed; a Response it returns is the answer."""
        self._before.append(hook)
        return hook

    def after_request(self, hook):
        """``hook(request, response)`` runs on every answer, the ones of errors and of hooks included."""
        self._after.append(hook)
        return hook

    def errorhandler(self, exc_type):
        """``handler(exc)`` returns the Response for an exception of ``exc_type`` (or a subclass) raised by a handler."""

        def register(handler):
            self._handlers[exc_type] = handler
            return handler

        return register

    def dispatch(self, request):
        try:
            response = self._respond(request)
        except Exception as exc:  # noqa: BLE001 - every failure becomes an answer
            response = self._error(exc)
        for hook in self._after:
            hook(request, response)
        return response

    def _respond(self, request):
        for hook in self._before:
            early = hook(request)
            if early is not None:
                return early
        allowed = set()
        for route in self.routes:
            params = route.match(request.path)
            if params is None:
                continue
            if route.method != request.method:
                allowed.add(route.method)
                continue
            request.params = params
            return route.handler(request)
        if allowed:
            raise HTTPError(405, "method not allowed", {"allow": ", ".join(sorted(allowed))})
        raise HTTPError(404, "not found")

    def _error(self, exc):
        for kind in type(exc).__mro__:
            handler = self._handlers.get(kind)
            if handler is not None:
                return handler(exc)
        if isinstance(exc, HTTPError):
            return json_response({"error": exc.message}, exc.status, exc.headers)
        return json_response({"error": "internal error"}, 500)
