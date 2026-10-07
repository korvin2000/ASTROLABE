import json
from urllib.parse import parse_qs

from .errors import HTTPError


class Request:
    """One request. ``args`` maps each query key to the list of its values, ``params`` holds the path parameters,
    ``state`` is a dict the hooks and the handler share. Header names are lower-case."""

    def __init__(self, method, path, query_string="", headers=None, body=b""):
        self.method = method.upper()
        self.path = path
        self.args = parse_qs(query_string, keep_blank_values=True)
        self.headers = {key.lower(): value for key, value in (headers or {}).items()}
        self.body = body
        self.params = {}
        self.state = {}

    def json(self):
        """The decoded body; anything that is not JSON answers 400 ``invalid json``."""
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise HTTPError(400, "invalid json") from None
