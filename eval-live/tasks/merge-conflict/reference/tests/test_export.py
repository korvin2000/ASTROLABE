import unittest

from spend.core.ledger import parse
from spend.core.money import format_cents, parse_amount
from spend.export import registry

LEDGER = "2026-03-02|groceries|54.20|market\n# skip\n2026-03-14|groceries|-4.50|refund\n"


class ExportTest(unittest.TestCase):
    def test_amounts(self):
        self.assertEqual([parse_amount(t) for t in ("12.5", "12.50", "-3", "0.07")], [1250, 1250, -300, 7])
        self.assertEqual([format_cents(c) for c in (1250, -450, 7)], ["12.50", "-4.50", "0.07"])

    def test_text(self):
        rendered = registry.get("text").render(parse(LEDGER))
        self.assertEqual(rendered.splitlines()[-1].split(), ["total", "49.70"])

    def test_markdown(self):
        rendered = registry.get("markdown").render(parse(LEDGER))
        self.assertIn("| 2026-03-14 | groceries | refund | -4.50 |", rendered)
        self.assertTrue(rendered.endswith("| | | **Total** | 49.70 |\n"))

    def test_registry(self):
        self.assertEqual(sorted(registry.EXPORTERS), ["csv", "json", "markdown", "text"])
        self.assertEqual(registry.for_path("out/March.MD").name, "markdown")
        self.assertEqual(registry.for_path("out/march.dat").name, "text")
        with self.assertRaises(ValueError):
            registry.get("pdf")


if __name__ == "__main__":
    unittest.main()
