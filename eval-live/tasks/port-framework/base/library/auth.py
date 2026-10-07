from tinyhttp import json_response

PROTECTED_METHODS = {"POST", "PUT", "DELETE"}


def require_token(tokens):
    """A before-request hook: every write needs ``Authorization: Bearer <token>``, whatever path it is sent to."""

    def hook(request):
        if request.method not in PROTECTED_METHODS:
            return None
        scheme, _, token = request.headers.get("authorization", "").partition(" ")
        if scheme != "Bearer" or token not in tokens:
            return json_response({"error": "unauthorized"}, 401, {"WWW-Authenticate": "Bearer"})
        return None

    return hook
