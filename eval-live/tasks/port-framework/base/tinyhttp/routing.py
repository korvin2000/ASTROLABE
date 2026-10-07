import re

_PARAM = re.compile(r"<(?:(int):)?([a-z_]+)>")


def compile_pattern(pattern):
    """``/books/<int:id>`` becomes a regex and the converters of its parameters."""
    converters = {}

    def sub(match):
        kind, name = match.groups()
        converters[name] = int if kind == "int" else str
        return "(?P<%s>%s)" % (name, r"\d+" if kind == "int" else r"[^/]+")

    return re.compile("^" + _PARAM.sub(sub, pattern) + "$"), converters


class Route:
    def __init__(self, method, pattern, handler):
        self.method = method
        self.pattern = pattern
        self.handler = handler
        self.regex, self.converters = compile_pattern(pattern)

    def match(self, path):
        found = self.regex.match(path)
        if found is None:
            return None
        return {name: self.converters[name](value) for name, value in found.groupdict().items()}
