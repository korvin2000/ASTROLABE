"""Case-insensitive headers and multi-valued query parameters."""

from urllib.parse import parse_qsl


def _text(value):
    return value.decode("latin-1") if isinstance(value, bytes) else str(value)


class Headers:
    """Header fields by case-insensitive name, one value per name; setting a name again replaces its value."""

    def __init__(self, raw=None):
        self._items = {}
        for key, value in raw.items() if hasattr(raw, "items") else (raw or ()):
            self[key] = value

    def __setitem__(self, key, value):
        self._items[_text(key).lower()] = _text(value)

    def __getitem__(self, key):
        return self._items[_text(key).lower()]

    def __contains__(self, key):
        return _text(key).lower() in self._items

    def get(self, key, default=None):
        return self._items.get(_text(key).lower(), default)

    def items(self):
        return self._items.items()

    def raw(self):
        """The ASGI form: a list of ``(name, value)`` byte pairs, names lower-case."""
        return [(key.encode("latin-1"), value.encode("latin-1")) for key, value in self._items.items()]


class QueryParams:
    """The query string's pairs. ``get`` answers the last value of a name, ``getlist`` all of them in order."""

    def __init__(self, query_string):
        self._pairs = parse_qsl(_text(query_string), keep_blank_values=True)

    def get(self, key, default=None):
        values = self.getlist(key)
        return values[-1] if values else default

    def getlist(self, key):
        return [value for name, value in self._pairs if name == key]

    def multi_items(self):
        return list(self._pairs)
