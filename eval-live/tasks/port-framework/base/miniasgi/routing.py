import re

from .exceptions import HTTPException
from .requests import Request

_PARAM = re.compile(r"\{([a-z_]+)(?::(int|str))?\}")


class Route:
    """``/books/{id:int}``: ``{name}`` matches one path segment, ``{name:int}`` digits only (and arrives as an int)."""

    def __init__(self, path, endpoint, methods):
        self.path = path
        self.endpoint = endpoint
        self.methods = {method.upper() for method in methods}
        self.converters = {}

        def sub(match):
            name, kind = match.groups()
            self.converters[name] = int if kind == "int" else str
            return "(?P<%s>%s)" % (name, r"\d+" if kind == "int" else r"[^/]+")

        self.regex = re.compile("^" + _PARAM.sub(sub, path) + "$")

    def match(self, path):
        found = self.regex.match(path)
        if found is None:
            return None
        return {name: self.converters[name](value) for name, value in found.groupdict().items()}


class Router:
    """Runs the first route whose path and method match; the endpoint is ``async def endpoint(request) -> Response``.
    Nothing matching raises HTTPException(404); a path that exists for other methods only, HTTPException(405)."""

    def __init__(self):
        self.routes = []

    def add_route(self, path, endpoint, methods=("GET",)):
        self.routes.append(Route(path, endpoint, methods))

    async def __call__(self, scope, receive, send):
        allowed = set()
        for route in self.routes:
            params = route.match(scope["path"])
            if params is None:
                continue
            if scope["method"] not in route.methods:
                allowed |= route.methods
                continue
            scope["path_params"] = params
            response = await route.endpoint(Request(scope, receive))
            await response(scope, receive, send)
            return
        if allowed:
            raise HTTPException(405, headers={"Allow": ", ".join(sorted(allowed))})
        raise HTTPException(404)
