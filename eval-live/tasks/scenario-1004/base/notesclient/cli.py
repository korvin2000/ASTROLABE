"""`python -m notesclient.cli [--url URL] list|show|add|rm`."""

import argparse
import sys

from notesclient.client import NotesClient, NotFound

DEFAULT_URL = "http://127.0.0.1:8040"


def main(argv=None, out=None):
    out = out or sys.stdout
    parser = argparse.ArgumentParser(prog="notes")
    parser.add_argument("--url", default=DEFAULT_URL)
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list")
    listing.add_argument("query", nargs="?", default="")
    listing.add_argument("--tag")
    show = commands.add_parser("show")
    show.add_argument("id", type=int)
    add = commands.add_parser("add")
    add.add_argument("title")
    add.add_argument("--body", default="")
    add.add_argument("--tag", action="append", default=[])
    remove = commands.add_parser("rm")
    remove.add_argument("id", type=int)
    args = parser.parse_args(argv)

    client = NotesClient(args.url)
    try:
        if args.command == "list":
            for note in client.list(args.query, args.tag):
                tags = " ".join("#" + t for t in note["tags"])
                out.write(f"{note['id']:>4}  {note['title']}{'  ' + tags if tags else ''}\n")
        elif args.command == "show":
            note = client.get(args.id)
            out.write(f"# {note['title']}\n\n{note['body']}\n")
        elif args.command == "add":
            note = client.add(args.title, args.body, args.tag)
            out.write(f"added {note['id']}\n")
        elif args.command == "rm":
            client.delete(args.id)
            out.write(f"removed {args.id}\n")
    except NotFound:
        out.write(f"no note {getattr(args, 'id', '')}".rstrip() + "\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
