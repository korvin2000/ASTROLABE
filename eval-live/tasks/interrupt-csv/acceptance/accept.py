"""Hidden acceptance of interrupt-csv: run from the root of a copy of the finished workspace.

The request asks for a weekly CSV export; the requirement added mid-task makes `;` its delimiter and freezes the
public `export_rows`. Both have to hold at the end.
"""

import csv
import datetime
import inspect
import io
import os
import re
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from reports import export  # noqa: E402
from reports.daily import export_daily  # noqa: E402
from reports.weekly import WeeklyReport  # noqa: E402

MONDAY = datetime.date(2026, 2, 23)
SALES = [
    (MONDAY, "books", 1250),
    (MONDAY, "books", 300),
    (MONDAY, "board games", 4999),
    (datetime.date(2026, 2, 25), "music", 5),
    (datetime.date(2026, 3, 1), "books", 120000),
    (datetime.date(2026, 3, 2), "books", 100),
]
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def read(path):
    with open(path, newline="", encoding="utf-8") as f:
        return f.read()


class WeeklyExport(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.report = WeeklyReport.from_sales(MONDAY, SALES)
        self.path = os.path.join(self.dir, "weekly.csv")
        self.assertTrue(hasattr(export, "export_weekly"), "reports.export has no export_weekly")
        export.export_weekly(self.report, self.path)
        self.text = read(self.path)
        self.lines = [line for line in self.text.splitlines() if line.strip()]

    def test_header_is_semicolon_separated(self):
        self.assertEqual(self.lines[0], "date;category;amount")

    def test_rows_use_semicolons_iso_dates_and_two_decimals(self):
        expected = [
            ["2026-02-23", "board games", "49.99"],
            ["2026-02-23", "books", "15.50"],
            ["2026-02-25", "music", "0.05"],
            ["2026-03-01", "books", "1200.00"],
        ]
        rows = list(csv.reader(io.StringIO(self.text), delimiter=";"))
        rows = [r for r in rows if r]
        self.assertEqual(rows[1:], expected)
        for line in self.lines[1:]:
            self.assertNotIn(",", line, "a comma in a row of the weekly export")
            self.assertTrue(ISO.match(line.split(";")[0]), line)


class ExportRowsUnchanged(unittest.TestCase):
    def test_signature(self):
        self.assertEqual(str(inspect.signature(export.export_rows)), "(rows, path)")

    def test_still_comma_separated(self):
        path = os.path.join(tempfile.mkdtemp(), "rows.csv")
        export.export_rows([["a", "b c"], ["1", "2"]], path)
        self.assertEqual(read(path), "a,b c\r\n1,2\r\n")

    def test_daily_export_unchanged(self):
        path = os.path.join(tempfile.mkdtemp(), "daily.csv")
        export_daily(SALES, MONDAY, path)
        self.assertEqual(read(path), "category,amount\r\nboard games,49.99\r\nbooks,15.50\r\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
