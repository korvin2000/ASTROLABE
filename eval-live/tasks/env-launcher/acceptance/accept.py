"""Hidden acceptance of env-launcher: run from the root of a copy of the finished workspace.

`dev.py test` must run the tests with the interpreter that runs `dev.py` (the only one the acceptance offers: the PATH
holds no `python` or `python3`) and exit non-zero when a test fails. On Windows the system finds a program named
`python` in the directory of the running interpreter whatever the PATH says, so `dev.py` is started from a fresh
virtual environment: a child started by name runs the base installation, a child started as `sys.executable` runs
the environment, and a probe test records which one ran (`sys.prefix`).
"""

import os
import shutil
import subprocess
import sys
import tempfile
import venv

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TESTS = os.path.join(ROOT, "tests")
MARK = os.path.join(ROOT, "_interpreter.txt")


def fail(message, output=""):
    print("FAIL:", message)
    if output:
        print(output[-4000:])
    sys.exit(1)


def interpreter(where):
    venv.EnvBuilder(with_pip=False, symlinks=(os.name != "nt"), clear=True).create(where)
    name = os.path.join("Scripts", "python.exe") if os.name == "nt" else os.path.join("bin", "python")
    return os.path.join(where, name)


def environment(empty_dir):
    env = {k: v for k, v in os.environ.items() if k.upper() not in ("PYTHONHOME", "PYTHONPATH", "VIRTUAL_ENV", "__PYVENV_LAUNCHER__", "PATH")}
    env["PATH"] = empty_dir
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def launch(python, env):
    if os.path.exists(MARK):
        os.remove(MARK)
    try:
        result = subprocess.run([python, "dev.py", "test"], cwd=ROOT, env=env, capture_output=True, text=True, timeout=60)
    except subprocess.TimeoutExpired:
        fail("`dev.py test` did not finish within 60 s")
    prefixes = []
    if os.path.exists(MARK):
        with open(MARK, encoding="utf-8") as f:
            prefixes = [line.strip() for line in f if line.strip()]
    return result.returncode, result.stdout + result.stderr, prefixes


def same(a, b):
    return os.path.normcase(os.path.realpath(a)) == os.path.normcase(os.path.realpath(b))


def main():
    scratch = tempfile.mkdtemp(prefix="env-launcher-")
    try:
        environment_dir = os.path.join(scratch, "venv")
        python = interpreter(environment_dir)
        empty = os.path.join(scratch, "empty-path")
        os.makedirs(empty)
        env = environment(empty)

        shutil.copyfile(os.path.join(HERE, "probe_interpreter.py"), os.path.join(TESTS, "test_zz_probe_interpreter.py"))
        code, output, prefixes = launch(python, env)
        if code != 0:
            fail(f"`dev.py test` exits with {code} on passing tests and a PATH without python or python3", output)
        if not prefixes:
            fail("`dev.py test` ran no test of tests/", output)
        if not all(same(p, environment_dir) for p in prefixes):
            fail(f"the tests ran with another interpreter ({prefixes[0]}) than the one running dev.py ({environment_dir})", output)
        print("ok: green tests pass with the interpreter running dev.py")

        shutil.copyfile(os.path.join(HERE, "probe_red.py"), os.path.join(TESTS, "test_zz_probe_red.py"))
        code, output, prefixes = launch(python, env)
        if not prefixes or not all(same(p, environment_dir) for p in prefixes):
            fail("with a failing test, `dev.py test` did not run the tests with the interpreter running dev.py", output)
        if code == 0:
            fail("`dev.py test` exits with 0 although a test fails", output)
        print(f"ok: a failing test makes `dev.py test` exit with {code}")
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    main()
