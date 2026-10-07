Note bodies are Markdown, but the notes app only ever shows them as source. Add an HTML view of a note:

- `GET /notes/<id>/html` answers `200` with `Content-Type: text/html; charset=utf-8` and the page
  `<article><h1>TITLE</h1>BODY</article>`, where TITLE is the note's title escaped with Python's `html.escape` and BODY
  is the note's body rendered by the `marklet` library; an unknown id answers `404` as the JSON view does.
- `NotesClient.html(note_id)` returns that page as a string (`NotFound` for an unknown id), and the command line's
  `show <id> --html` prints it followed by a newline.

`marklet` 1.2.0 is not installed yet. It is in `vendor/` as a wheel and this machine has no network: install it from
there into the project's virtual environment `.venv` (create it), and declare it in `requirements.txt` as
`marklet==1.2.0`. Do not change anything in `vendor/`. Keep the existing tests passing.
