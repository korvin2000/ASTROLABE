from http import HTTPStatus


class HTTPException(Exception):
    """Raised by endpoints, and by the router for an unknown path (404) or a method the path does not take (405, with
    its ``Allow`` header). Without a ``detail`` it is the status phrase, for example ``Not Found``."""

    def __init__(self, status_code, detail=None, headers=None):
        self.status_code = status_code
        self.detail = detail if detail is not None else HTTPStatus(status_code).phrase
        self.headers = dict(headers or {})
        super().__init__(self.detail)
