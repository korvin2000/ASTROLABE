import json


class Response:
    """An answer: status, body bytes and headers (names lower-case, so hooks can overwrite them)."""

    def __init__(self, status=200, body=b"", headers=None):
        self.status = status
        self.body = body
        self.headers = {key.lower(): value for key, value in (headers or {}).items()}


def json_response(data, status=200, headers=None):
    response = Response(status, json.dumps(data, sort_keys=True).encode("utf-8"), headers)
    response.headers.setdefault("content-type", "application/json")
    return response
