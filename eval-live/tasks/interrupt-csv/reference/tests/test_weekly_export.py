import datetime
import os
import tempfile
import unittest

from reports.export import export_weekly
from reports.weekly import WeeklyReport

MONDAY = datetime.date(2026, 3, 2)


class WeeklyExportTest(unittest.TestCase):
    def test_header_iso_dates_and_semicolons(self):
        report = WeeklyReport.from_sales(MONDAY, [(MONDAY, "books", 1550), (datetime.date(2026, 3, 4), "games", 800)])
        path = os.path.join(tempfile.mkdtemp(), "weekly.csv")
        export_weekly(report, path)
        with open(path, newline="", encoding="utf-8") as f:
            self.assertEqual(f.read(), "date;category;amount\r\n2026-03-02;books;15.50\r\n2026-03-04;games;8.00\r\n")


if __name__ == "__main__":
    unittest.main()
