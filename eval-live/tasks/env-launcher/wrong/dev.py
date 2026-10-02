"""Developer commands of this project: `python dev.py test` runs the unit tests, `python dev.py clean` removes caches."""

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
COMMANDS = {}


def command(fn):
    COMMANDS[fn.__name__] = fn
    return fn


@command
def test():
    """Runs every test under tests/; fails when a test fails."""
    return subprocess.call(["python", "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=ROOT)


@command
def clean():
    """Removes the __pycache__ directories."""
    for cache in ROOT.rglob("__pycache__"):
        shutil.rmtree(cache, ignore_errors=True)
    return 0


def main(argv):
    if len(argv) != 1 or argv[0] not in COMMANDS:
        print("usage: python dev.py " + "|".join(sorted(COMMANDS)), file=sys.stderr)
        return 2
    return COMMANDS[argv[0]]()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
