# notes

A small client-server notes application, Python standard library only:

- `notes/` — the store (`NoteStore`, a JSON file), search, and the HTTP API (`python -m notes.server --port 8040`);
- `notesclient/` — `NotesClient` and the command line (`python -m notesclient.cli list|show|add|rm`).

Note bodies are Markdown. Third-party packages are installed offline from `vendor/` (see `vendor/README.txt`) into
the project's virtual environment `.venv`.

Tests: `python -m unittest discover -s tests`.
