import datetime
import os
import tempfile
import unittest

from reports.daily import export_daily
from reports.export import export_rows
from reports.weekly import Entry, WeeklyReport

MONDAY = datetime.date(2026, 3, 2)
SALES = [
    (MONDAY, "books", 1250),
    (MONDAY, "books", 300),
    (MONDAY, "games", 4999),
    (MONDAY + datetime.timedelta(days=2), "books", 800),
    (MONDAY + datetime.timedelta(days=7), "books", 100),
]


class WeeklyTest(unittest.TestCase):
    def test_sales_add_up_per_day_and_category(self):
        report = WeeklyReport.from_sales(MONDAY, SALES)
        self.assertEqual(report.entries(), [
            Entry(MONDAY, "books", 1550),
            Entry(MONDAY, "games", 4999),
            Entry(datetime.date(2026, 3, 4), "books", 800),
        ])
        self.assertEqual(report.total_cents(), 7349)

    def test_display_rows(self):
        report = WeeklyReport.from_sales(MONDAY, SALES)
        self.assertEqual(report.display_rows()[0], ["03/02/2026", "books", "15.50"])

    def test_week_starts_on_monday(self):
        with self.assertRaises(ValueError):
            WeeklyReport(datetime.date(2026, 3, 3), [])


class ExportTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def read(self, name):
        with open(os.path.join(self.dir, name), newline="", encoding="utf-8") as f:
            return f.read()

    def test_export_rows_writes_comma_separated_lines(self):
        export_rows([["a", "b"], ["1", "2"]], os.path.join(self.dir, "rows.csv"))
        self.assertEqual(self.read("rows.csv"), "a,b\r\n1,2\r\n")

    def test_daily_export(self):
        export_daily(SALES, MONDAY, os.path.join(self.dir, "daily.csv"))
        self.assertEqual(self.read("daily.csv"), "category,amount\r\nbooks,15.50\r\ngames,49.99\r\n")


if __name__ == "__main__":
    unittest.main()
