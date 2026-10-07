"""A notes server on a free port, in a thread, for the duration of a test."""

import contextlib
import threading

from notes.api import make_server
from notes.store import NoteStore


@contextlib.contextmanager
def running(store=None):
    store = store or NoteStore()
    server = make_server(store, port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield store, f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()
