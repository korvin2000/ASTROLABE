"""The HTML view of a note."""

import html


def note_html(note):
    # marklet is installed from vendor/ (requirements.txt); imported here so the JSON API runs without it.
    import marklet

    return f"<article><h1>{html.escape(note.title)}</h1>{marklet.render(note.body)}</article>"
