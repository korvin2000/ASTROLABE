import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from billing.aggregate import grand_total, month_window, monthly_totals
from billing.normalize import Invoice
from billing.report import build_report

HEADER = "id,customer,issued_at,amount,status\n"


def invoice(issued_at, amount):
    return Invoice("X", "Acme", datetime.fromisoformat(issued_at), amount)


class MonthBoundaryTest(unittest.TestCase):
    def test_window_is_half_open_also_in_december(self):
        self.assertEqual(month_window(2024, 2), (datetime(2024, 2, 1), datetime(2024, 3, 1)))
        self.assertEqual(month_window(2023, 12), (datetime(2023, 12, 1), datetime(2024, 1, 1)))

    def test_midnight_on_the_first_belongs_to_the_new_month_only(self):
        invoices = [invoice("2024-01-31T12:00:00", 100.0), invoice("2024-02-01T00:00:00", 480.0)]
        january, february = monthly_totals(invoices)
        self.assertEqual((january.count, january.amount), (1, 100.0))
        self.assertEqual((february.count, february.amount), (1, 480.0))

    def test_months_add_up_to_the_total_over_a_leap_february_and_a_new_year(self):
        invoices = [
            invoice("2023-12-31T23:59:59.999999", 1.0),
            invoice("2024-01-01T00:00:00", 2.0),
            invoice("2024-02-29T23:59:59.999999", 4.0),
            invoice("2024-03-01T00:00:00", 8.0),
        ]
        self.assertEqual([total.count for total in monthly_totals(invoices)], [1, 1, 1, 1])
        self.assertEqual(sum(total.amount for total in monthly_totals(invoices)), grand_total(invoices))

    def test_report_of_a_file_with_midnight_invoices(self):
        body = "A-1,Acme,2024-01-31T09:00:00,10.00,paid\nA-2,Acme,2024-02-01T00:00:00,20.00,paid\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invoices.csv"
            path.write_text(HEADER + body, encoding="utf-8", newline="\n")
            self.assertEqual(build_report(path), "Monthly totals\n2024-01  n=1  10.00\n2024-02  n=1  20.00\nTotal  n=2  30.00")


if __name__ == "__main__":
    unittest.main()
