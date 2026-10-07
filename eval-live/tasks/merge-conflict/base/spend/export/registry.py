"""The export formats, by name: what `spend export --format` accepts and what `spend formats` lists."""

from dataclasses import dataclass

from spend.export import markdown, text


@dataclass(frozen=True)
class Exporter:
    name: str
    # The file extension the format is chosen by when `--output` names a file and `--format` is not given.
    extension: str
    description: str
    render: object


EXPORTERS = {
    "markdown": Exporter("markdown", ".md", "Markdown table with a total row", markdown.render),
    "text": Exporter("text", ".txt", "Plain text, one line per expense", text.render),
}

DEFAULT = "text"


def get(name):
    try:
        return EXPORTERS[name]
    except KeyError:
        raise ValueError(f"unknown format {name!r}; known: {', '.join(sorted(EXPORTERS))}") from None


def for_path(path):
    """The exporter whose extension `path` ends with, else the default one."""
    lowered = str(path).lower()
    for exporter in EXPORTERS.values():
        if lowered.endswith(exporter.extension):
            return exporter
    return EXPORTERS[DEFAULT]


def listing():
    """The lines of `spend formats`: name, extension and description, sorted by name."""
    return [f"{e.name:<10}{e.extension:<7}{e.description}" for e in sorted(EXPORTERS.values(), key=lambda e: e.name)]
