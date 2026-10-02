Create a small todo REST API in `server.py` at the project root, using only the Python standard library (no
third-party packages). `python server.py --port 8080` starts it on 127.0.0.1 and serves until it is stopped. Every
response body is JSON with the content type `application/json`, except the empty body of a 204.

- `GET /health` → 200 `{"status": "ok"}`
- `GET /todos` → 200, the list of todos in creation order
- `POST /todos` with `{"title": "..."}` → 201 with the new todo `{"id": 1, "title": "...", "done": false}`; ids start
  at 1 and increase by one
- `GET /todos/<id>` → 200 with the todo, 404 when there is none
- `PATCH /todos/<id>` with `{"done": true}` and/or `{"title": "..."}` → 200 with the updated todo, 404 when unknown
- `DELETE /todos/<id>` → 204 with an empty body, 404 when unknown
- invalid JSON, a missing or empty title on `POST`, or a field of the wrong type (`title` not a string, `done` not a
  boolean) → 400 with `{"error": "<message>"}`
- any other path → 404 with `{"error": "<message>"}`

Todos live in memory only. `tests/test_health.py` shows how the server is started; keep it passing.
