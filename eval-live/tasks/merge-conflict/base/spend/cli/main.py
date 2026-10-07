"""`spend export <ledger> [--format NAME] [--output PATH]` and `spend formats`."""

import argparse
import sys

from spend.core.ledger import load
from spend.export import registry


def main(argv=None, out=None):
    out = out or sys.stdout
    parser = argparse.ArgumentParser(prog="spend")
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="export a ledger")
    export.add_argument("ledger")
    export.add_argument("--format", choices=sorted(registry.EXPORTERS))
    export.add_argument("--output")
    commands.add_parser("formats", help="list the export formats")
    args = parser.parse_args(argv)

    if args.command == "formats":
        out.write("\n".join(registry.listing()) + "\n")
        return 0
    if args.format:
        exporter = registry.get(args.format)
    elif args.output:
        exporter = registry.for_path(args.output)
    else:
        exporter = registry.get(registry.DEFAULT)
    rendered = exporter.render(load(args.ledger))
    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="") as f:
            f.write(rendered)
        out.write(f"wrote {args.output} ({exporter.name})\n")
    else:
        out.write(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
