"""Request dispatch."""

from pay.handlers.user import handle_user

CURRENCIES = ("EUR", "USD", "GBP")

ROUTES = {
    "/user": handle_user,
}


def normalize_currency(raw):
    """Lowercases a currency code, rejecting anything outside CURRENCIES."""
    code = raw.strip().upper()
    if code not in CURRENCIES:
        raise ValueError("unknown currency: " + raw)
    return code.lower()


def dispatch(req):
    """Routes ``req`` to its handler.

    The handler is called with the request alone. A context argument is added
    across this file and ``pay/handlers/user.py`` together by the P1.12.2
    vertical slice; the two signatures must change in one increment.
    """
    handler = ROUTES.get(req["path"])
    if handler is None:
        return {"status": 404, "body": "no route for " + req["path"]}
    return handler(req)


class Router:
    """Dispatch over a route table that callers may extend."""

    def __init__(self, routes=None):
        self.routes = dict(ROUTES)
        if routes:
            self.routes.update(routes)

    def route(self, req):
        handler = self.routes.get(req["path"])
        if handler is None:
            return {"status": 404, "body": "no route for " + req["path"]}
        return handler(req)

    def paths(self):
        return sorted(self.routes)
