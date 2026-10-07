import io
import os
import tempfile
import unittest

from spend.cli.main import main

HERE = os.path.dirname(os.path.abspath(__file__))
LEDGER = os.path.join(HERE, os.pardir, "examples", "march.ledger")


class CliTest(unittest.TestCase):
    def test_formats(self):
        out = io.StringIO()
        main(["formats"], out)
        self.assertEqual([line.split()[0] for line in out.getvalue().splitlines()], ["csv", "json", "markdown", "text"])

    def test_export_by_extension(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = os.path.join(tmp, "march.md")
            out = io.StringIO()
            main(["export", LEDGER, "--output", target], out)
            self.assertEqual(out.getvalue(), f"wrote {target} (markdown)\n")
            with open(target, encoding="utf-8") as f:
                self.assertTrue(f.read().startswith("| Date |"))

    def test_export_to_stdout(self):
        out = io.StringIO()
        main(["export", LEDGER], out)
        self.assertIn("transport", out.getvalue())


if __name__ == "__main__":
    unittest.main()
