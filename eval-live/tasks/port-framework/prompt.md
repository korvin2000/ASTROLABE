The catalogue service in `library/` is written on our in-house `tinyhttp` micro-framework (`http.server` underneath).
Every service of the company is moving to `miniasgi`, the ASGI-style framework that already sits in this repository
(`miniasgi/`, with its own test client and a small `serve`). Port the service to it.

- `library.app.create_app(store=None, tokens=("dev-token",))` keeps its signature and returns a `miniasgi.App`;
  `python -m library` starts it with `miniasgi`'s server.
- Clients must not notice the move. Every status code, header and JSON body stays what it is today, the error answers
  and the cases the old framework handled implicitly included: `tests/test_api.py` shows part of the contract, the rest
  is what the code of `library/` and `tinyhttp/` does. `library/models.py` and `library/store.py` stay as they are.
- Port `tests/test_api.py` to the `miniasgi` test client, so that `python -m unittest discover -s tests` passes.
- When you are done, nothing under `library/` or `tests/` may import `tinyhttp` or `http.server`. You may delete
  `tinyhttp/` once nothing needs it.
