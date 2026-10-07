"""Hidden acceptance of merge-conflict: both formats, nothing lost, nothing twice. Run from the root of a copy of the finished workspace."""

import ast
import collections
import io
import json
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from spend.cli.main import main  # noqa: E402
from spend.core.ledger import parse  # noqa: E402
from spend.export import registry  # noqa: E402

LEDGER = (
    "# April\n"
    "2026-04-01|groceries|54.20|market, weekly\n"
    "2026-04-03|books|18|\"Dune\", paperback\n"
    "2026-04-07|café|3.10|crème brûlée\n"
    "2026-04-09|groceries|-4.50|refund\n"
)
CSV = (
    "date,category,note,amount\n"
    "2026-04-01,groceries,\"market, weekly\",54.20\n"
    "2026-04-03,books,\"\"\"Dune\"\", paperback\",18.00\n"
    "2026-04-07,café,crème brûlée,3.10\n"
    "2026-04-09,groceries,refund,-4.50\n"
)
DOCUMENT = {
    "expenses": [
        {"date": "2026-04-01", "category": "groceries", "note": "market, weekly", "amount_cents": 5420},
        {"date": "2026-04-03", "category": "books", "note": "\"Dune\", paperback", "amount_cents": 1800},
        {"date": "2026-04-07", "category": "café", "note": "crème brûlée", "amount_cents": 310},
        {"date": "2026-04-09", "category": "groceries", "note": "refund", "amount_cents": -450},
    ],
    "total_cents": 7080,
}
TEXT_TAIL = "total                        70.80"
MARKDOWN_ROW = "| 2026-04-07 | café | crème brûlée | 3.10 |"


def expenses():
    return parse(LEDGER)


class BothFormats(unittest.TestCase):
    def test_csv(self):
        self.assertEqual(registry.get("csv").render(expenses()), CSV)

    def test_json(self):
        rendered = registry.get("json").render(expenses())
        self.assertEqual(json.loads(rendered), DOCUMENT)
        self.assertEqual(rendered, json.dumps(DOCUMENT, indent=2, ensure_ascii=False) + "\n")

    def test_chosen_by_extension(self):
        self.assertEqual(registry.for_path("out/April.CSV").name, "csv")
        self.assertEqual(registry.for_path("out/april.json").name, "json")
        self.assertEqual(registry.for_path("out/april.md").name, "markdown")
        self.assertEqual(registry.for_path("out/april.txt").name, "text")
        self.assertEqual(registry.for_path("out/april").name, "text")


class NothingLost(unittest.TestCase):
    def test_four_formats(self):
        self.assertEqual(sorted(registry.EXPORTERS), ["csv", "json", "markdown", "text"])
        for name, exporter in registry.EXPORTERS.items():
            self.assertEqual(exporter.name, name)
        self.assertEqual(registry.get("csv").description, "CSV: date, category, note, amount")
        self.assertEqual(registry.get("json").description, "JSON document with the total")
        self.assertEqual((registry.get("csv").extension, registry.get("json").extension), (".csv", ".json"))

    def test_old_formats_unchanged(self):
        self.assertEqual(registry.get("text").render(expenses()).splitlines()[-1], TEXT_TAIL)
        markdown = registry.get("markdown").render(expenses())
        self.assertIn(MARKDOWN_ROW, markdown)
        self.assertTrue(markdown.endswith("| | | **Total** | 70.80 |\n"))
        self.assertEqual(registry.get("markdown").description, "Markdown table with a total row")
        self.assertEqual(registry.get("text").description, "Plain text, one line per expense")


class NothingTwice(unittest.TestCase):
    def test_registry_source_has_no_duplicates(self):
        path = os.path.join(ROOT, "spend", "export", "registry.py")
        with open(path, encoding="utf-8") as f:
            tree = ast.parse(f.read())
        for node in ast.walk(tree):
            if isinstance(node, ast.Dict):
                keys = [k.value for k in node.keys if isinstance(k, ast.Constant)]
                twice = [k for k, n in collections.Counter(keys).items() if n > 1]
                self.assertEqual(twice, [], f"a dict literal in registry.py names {twice} more than once")
        defined = [n.name for n in tree.body if isinstance(n, (ast.FunctionDef, ast.ClassDef))]
        self.assertEqual(sorted(set(defined)), sorted(defined), f"registry.py defines a name twice: {defined}")
        assigned = [t.id for n in tree.body if isinstance(n, ast.Assign) for t in n.targets if isinstance(t, ast.Name)]
        self.assertEqual(assigned.count("EXPORTERS"), 1, "EXPORTERS is assigned once")

    def test_listing_once_each(self):
        out = io.StringIO()
        main(["formats"], out)
        names = [line.split()[0] for line in out.getvalue().splitlines() if line.strip()]
        self.assertEqual(names, ["csv", "json", "markdown", "text"])


class CommandLine(unittest.TestCase):
    def test_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            ledger = os.path.join(tmp, "april.ledger")
            with open(ledger, "w", encoding="utf-8") as f:
                f.write(LEDGER)
            out = io.StringIO()
            main(["export", ledger, "--format", "csv"], out)
            self.assertEqual(out.getvalue(), CSV)
            target = os.path.join(tmp, "april.json")
            out = io.StringIO()
            main(["export", ledger, "--output", target], out)
            self.assertEqual(out.getvalue(), f"wrote {target} (json)\n")
            with open(target, encoding="utf-8") as f:
                self.assertEqual(json.load(f), DOCUMENT)
            target = os.path.join(tmp, "april.csv")
            main(["export", ledger, "--output", target], io.StringIO())
            with open(target, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), CSV)


if __name__ == "__main__":
    unittest.main(verbosity=1)
