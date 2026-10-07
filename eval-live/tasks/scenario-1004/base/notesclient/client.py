"""A client of the notes API (`urllib`, JSON)."""

import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class NotFound(Exception):
    pass


class NotesClient:
    def __init__(self, base_url, timeout=10):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def list(self, query="", tag=None):
        params = []
        if query:
            params.append("q=" + "+".join(query.split()))
        if tag:
            params.append("tag=" + tag)
        return self._call("GET", "/notes" + ("?" + "&".join(params) if params else ""))

    def get(self, note_id):
        return self._call("GET", f"/notes/{note_id}")

    def add(self, title, body="", tags=()):
        return self._call("POST", "/notes", {"title": title, "body": body, "tags": list(tags)})

    def delete(self, note_id):
        self._call("DELETE", f"/notes/{note_id}")

    def _call(self, method, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(self.base_url + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
        except HTTPError as error:
            if error.code == 404:
                raise NotFound(path) from None
            raise
        return json.loads(raw) if raw else None
