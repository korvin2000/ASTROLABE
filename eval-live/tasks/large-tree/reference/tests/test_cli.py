import io
import unittest
from contextlib import redirect_stdout

from parcelhub.cli.formatting import flags
from parcelhub.cli.main import main

from support import parcel


class Cli(unittest.TestCase):
    def run_cli(self, *argv):
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(list(argv))
        return code, out.getvalue()

    def test_flags(self):
        self.assertEqual(flags(parcel(30)), "")
        self.assertEqual(flags(parcel(130, 26000)), "BH")
        self.assertEqual(flags(parcel(110)), "")

    def test_quote_prints_the_lines_and_the_total(self):
        code, text = self.run_cli("quote", "30x20x10", "400", "DE")
        self.assertEqual(code, 0)
        self.assertIn("total    4.20 EUR", text)

    def test_describe_names_the_carrier(self):
        code, text = self.run_cli("describe", "130x20x10", "400", "DE")
        self.assertEqual(code, 0)
        self.assertIn("freight", text)
        self.assertTrue(text.rstrip().endswith("B"))

    def test_bad_usage(self):
        self.assertEqual(self.run_cli("nope")[0], 2)


if __name__ == "__main__":
    unittest.main()
