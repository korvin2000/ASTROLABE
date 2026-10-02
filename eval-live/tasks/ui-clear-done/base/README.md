# todo-app

A todo list: `server.py` (Python standard library) serves the JSON API and the page in `static/` (plain HTML and
JavaScript, no build step).

```
python server.py --port 8000     # then open http://127.0.0.1:8000/
```

API (JSON): `GET /api/todos`, `POST /api/todos` with `{"title": "..."}`, `PATCH /api/todos/<id>` with `{"done": true}`
and/or `{"title": "..."}`. Todos live in memory; ids are never reused.

Tests: `python -m unittest discover -s tests`.
