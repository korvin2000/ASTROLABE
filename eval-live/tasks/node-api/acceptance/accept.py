"""Hidden acceptance of node-api: runs `node _acceptance/check.mjs` from the root of a copy of the finished workspace."""

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    node = shutil.which("node")
    if node is None:
        print("FAILED: node is not on the PATH; the node-api acceptance needs Node.js 18 or later (node --test, fetch)")
        return 2
    version = subprocess.run([node, "--version"], capture_output=True, text=True, timeout=30)
    print(f"node {version.stdout.strip()}")
    check = os.path.join(ROOT, "_acceptance", "check.mjs")
    try:
        result = subprocess.run([node, check], cwd=ROOT, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=100)
    except subprocess.TimeoutExpired:
        print("FAILED: the checks did not end within 100 s")
        return 1
    sys.stdout.write(result.stdout)
    sys.stdout.write(result.stderr)
    if result.returncode != 0:
        print(f"FAILED: node exited {result.returncode}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
