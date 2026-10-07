# shelf

A small book catalogue service. `library/` is the service, `tinyhttp/` the in-house micro-framework it is written on
(`http.server` underneath), `miniasgi/` the ASGI-style framework the other services of the company already run on.

    python -m library            # serves on SHELF_PORT (default 8000); SHELF_TOKENS="a,b" sets the write tokens
    python -m unittest discover -s tests

Reads are open; every write (`POST`, `PUT`, `DELETE`) needs `Authorization: Bearer <token>`.
