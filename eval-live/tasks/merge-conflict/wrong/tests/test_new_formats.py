import json
import unittest

from spend.core.ledger import parse
from spend.export import registry

LEDGER = "2026-03-02|groceries|54.20|market, weekly\n2026-03-14|café|-4.50|refund\n"


class NewFormatsTest(unittest.TestCase):
    def test_csv(self):
        rendered = registry.get("csv").render(parse(LEDGER))
        self.assertEqual(rendered, "date,category,note,amount\n2026-03-02,groceries,\"market, weekly\",54.20\n2026-03-14,café,refund,-4.50\n")
        self.assertEqual(registry.for_path("march.csv").name, "csv")

    def test_json(self):
        rendered = registry.get("json").render(parse(LEDGER))
        document = json.loads(rendered)
        self.assertEqual(document["total_cents"], 4970)
        self.assertEqual(document["expenses"][1], {"date": "2026-03-14", "category": "café", "note": "refund", "amount_cents": -450})
        self.assertIn("café", rendered)
        self.assertEqual(registry.for_path("march.json").name, "json")


if __name__ == "__main__":
    unittest.main()
