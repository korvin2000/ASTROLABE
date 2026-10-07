"""`python -m notes.server [--port N] [--data notes.json]`: serves the notes API until interrupted."""

import argparse

from notes.api import make_server
from notes.store import NoteStore


def main(argv=None):
    parser = argparse.ArgumentParser(prog="notes.server")
    parser.add_argument("--port", type=int, default=8040)
    parser.add_argument("--data", default="notes.json")
    args = parser.parse_args(argv)
    server = make_server(NoteStore(args.data), port=args.port)
    print(f"notes on http://127.0.0.1:{server.server_address[1]}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
