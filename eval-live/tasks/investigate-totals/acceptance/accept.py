"""Hidden acceptance of investigate-totals: run from the root of a copy of the finished workspace.

Every check goes through the public entry `billing.report.build_report(path)` on CSV files this acceptance writes
(and on its own copy of the repro data), so it does not depend on how the stages are written inside. Expected texts are
literals or come from an independent oracle on `decimal`; none of them depends on how amounts are rounded.
"""

import csv
import random
import sys
import tempfile
import unittest
from datetime import datetime
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))

from billing.report import build_report  # noqa: E402

HEADER = ["id", "customer", "issued_at", "amount", "status"]


def report_of(rows, directory):
    """The report of a CSV with the given (issued_at, amount[, status]) rows."""
    path = Path(directory) / "invoices.csv"
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(HEADER)
        for number, row in enumerate(rows, 1):
            issued_at, amount, *status = row
            writer.writerow([f"T-{number}", "Acme", issued_at, amount, status[0] if status else "paid"])
    return build_report(path)


def oracle(rows):
    """The expected report, computed with decimal and plain (year, month) grouping."""
    months = {}
    count, total = 0, Decimal("0")
    for issued_at, amount, *status in rows:
        if status and status[0] == "void":
            continue
        moment = datetime.fromisoformat(issued_at)
        value = Decimal(str(amount).replace(",", ""))
        n, subtotal = months.get((moment.year, moment.month), (0, Decimal("0")))
        months[(moment.year, moment.month)] = (n + 1, subtotal + value)
        count, total = count + 1, total + value
    lines = ["Monthly totals"]
    for (year, month), (n, subtotal) in sorted(months.items()):
        lines.append(f"{year}-{month:02d}  n={n}  {subtotal:,.2f}")
    lines.append(f"Total  n={count}  {total:,.2f}")
    return "\n".join(lines)


class Boundaries(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def report(self, rows):
        return report_of(rows, self.directory.name)

    def test_repro_data(self):
        expected = "\n".join([
            "Monthly totals",
            "2023-12  n=5  3,125.89",
            "2024-01  n=7  5,313.41",
            "2024-02  n=7  3,271.52",
            "2024-03  n=4  2,134.25",
            "Total  n=23  13,845.07",
        ])
        self.assertEqual(build_report(HERE / "invoices.csv"), expected)

    def test_midnight_on_the_first_belongs_to_the_new_month_only(self):
        rows = [("2024-01-31T12:00:00", "100.00"), ("2024-02-01T00:00:00", "250.50"), ("2024-02-15T08:00:00", "0.25")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-01  n=1  100.00\n2024-02  n=2  250.75\nTotal  n=3  350.75")

    def test_last_instant_of_a_month_stays_in_it(self):
        rows = [("2024-01-31T23:59:59.999999", "7.00"), ("2024-02-01T00:00:00", "5.00")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-01  n=1  7.00\n2024-02  n=1  5.00\nTotal  n=2  12.00")

    def test_leap_february(self):
        rows = [("2024-02-28T23:59:59", "1.00"), ("2024-02-29T23:59:59.999999", "2.00"), ("2024-03-01T00:00:00", "4.00")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-02  n=2  3.00\n2024-03  n=1  4.00\nTotal  n=3  7.00")

    def test_february_of_a_common_year(self):
        rows = [("2023-02-28T23:59:59.999999", "1.00"), ("2023-03-01T00:00:00", "4.00")]
        self.assertEqual(self.report(rows), "Monthly totals\n2023-02  n=1  1.00\n2023-03  n=1  4.00\nTotal  n=2  5.00")

    def test_new_year_and_the_other_ways_to_write_midnight(self):
        rows = [("2023-12-31T23:59:59.999999", "10.00"), ("2024-01-01", "20.00"), ("2024-02-01 00:00:00", "40.00")]
        self.assertEqual(self.report(rows), "Monthly totals\n2023-12  n=1  10.00\n2024-01  n=1  20.00\n2024-02  n=1  40.00\nTotal  n=3  70.00")

    def test_void_invoice_at_midnight_counts_nowhere(self):
        rows = [("2024-04-30T10:00:00", "3.00"), ("2024-05-01T00:00:00", "9.00", "void"), ("2024-05-02T10:00:00", "1.00", "open")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-04  n=1  3.00\n2024-05  n=1  1.00\nTotal  n=2  4.00")

    def test_every_month_edge_of_two_years(self):
        rows = []
        for year in (2023, 2024):
            for month in range(1, 13):
                last_day = (datetime(year + (month == 12), month % 12 + 1, 1) - datetime(year, month, 1)).days
                rows.append((f"{year}-{month:02d}-01T00:00:00", f"{month}0.10"))
                rows.append((f"{year}-{month:02d}-{last_day:02d}T23:59:59.999999", f"{month}.05"))
        text = self.report(rows)
        self.assertEqual(text, oracle(rows))
        lines = text.splitlines()
        self.assertEqual(sum(int(line.split("n=")[1].split()[0]) for line in lines[1:-1]), 48)
        self.assertEqual(sum(Decimal(line.split()[-1].replace(",", "")) for line in lines[1:-1]), Decimal(lines[-1].split()[-1].replace(",", "")))

    def test_seeded_invoices_around_month_edges(self):
        generator = random.Random(2024)
        rows = []
        for _ in range(400):
            year, month = generator.choice([2022, 2023, 2024, 2025]), generator.randint(1, 12)
            last_day = (datetime(year + (month == 12), month % 12 + 1, 1) - datetime(year, month, 1)).days
            kind = generator.choice(["first", "last", "any", "any"])
            if kind == "first":
                stamp = f"{year}-{month:02d}-01" + generator.choice(["T00:00:00", " 00:00:00", ""])
            elif kind == "last":
                stamp = f"{year}-{month:02d}-{last_day:02d}T23:59:59.999999"
            else:
                stamp = f"{year}-{month:02d}-{generator.randint(1, last_day):02d}T{generator.randint(0, 23):02d}:{generator.randint(0, 59):02d}:00"
            amount = f"{generator.randint(0, 250000) / 100:.2f}"
            rows.append((stamp, amount, generator.choice(["paid", "paid", "open", "void"])))
        self.assertEqual(self.report(rows), oracle(rows))


class RoundingDoesNotMatter(unittest.TestCase):
    """The sums are what plain cent arithmetic gives, whatever the stages use inside.

    No invoice sits on a month edge here, so these pass on the base too: rounding was never the problem.
    """

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def report(self, rows):
        return report_of(rows, self.directory.name)

    def test_many_small_amounts(self):
        rows = [("2024-06-10T10:00:00", "0.10")] * 100 + [("2024-06-11T10:00:00", "0.20")] * 3
        self.assertEqual(self.report(rows), "Monthly totals\n2024-06  n=103  10.60\nTotal  n=103  10.60")

    def test_classic_float_sums(self):
        rows = [("2024-07-01T09:00:00", "0.10"), ("2024-07-02T09:00:00", "0.20"), ("2024-07-03T09:00:00", "0.30"), ("2024-08-02T00:00:00", "19.99")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-07  n=3  0.60\n2024-08  n=1  19.99\nTotal  n=4  20.59")

    def test_large_amounts_and_thousands_separators(self):
        rows = [("2024-09-05T09:00:00", "1,234,567.89"), ("2024-09-06T09:00:00", "0.01"), ("2024-10-03T00:00:00", "999,999.99")]
        self.assertEqual(self.report(rows), "Monthly totals\n2024-09  n=2  1,234,567.90\n2024-10  n=1  999,999.99\nTotal  n=3  2,234,567.89")

    def test_repeated_cents_over_a_month(self):
        rows = [(f"2024-11-{day:02d}T12:00:00", "33.33") for day in range(1, 31)]
        self.assertEqual(self.report(rows), oracle(rows))
        self.assertEqual(self.report(rows).splitlines()[1], "2024-11  n=30  999.90")


if __name__ == "__main__":
    unittest.main(verbosity=2)
