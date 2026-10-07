class HTTPError(Exception):
    """Raised by a handler to answer with a status; the app turns it into ``{"error": message}``."""

    def __init__(self, status, message, headers=None):
        super().__init__(message)
        self.status = status
        self.message = message
        self.headers = dict(headers or {})
