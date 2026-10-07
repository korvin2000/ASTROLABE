from miniasgi import BaseHTTPMiddleware, JSONResponse

PROTECTED_METHODS = {"POST", "PUT", "DELETE"}


class RequireToken(BaseHTTPMiddleware):
    """Every write needs ``Authorization: Bearer <token>``, whatever path it is sent to. It answers instead of
    raising: an exception raised in middleware is not seen by the app's exception handlers."""

    def __init__(self, app, tokens):
        super().__init__(app)
        self.tokens = set(tokens)

    async def dispatch(self, request, call_next):
        if request.method in PROTECTED_METHODS:
            scheme, _, token = request.headers.get("authorization", "").partition(" ")
            if scheme != "Bearer" or token not in self.tokens:
                return JSONResponse({"error": "unauthorized"}, 401, {"WWW-Authenticate": "Bearer"})
        return await call_next(request)
