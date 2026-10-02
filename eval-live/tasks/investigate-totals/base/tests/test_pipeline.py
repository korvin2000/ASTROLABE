import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from billing.format import format_amount
from billing.loader import load_rows
from billing.normalize import normalize
from billing.report import build_report

HEADER = "id,customer,issued_at,amount,status\n"


def write_csv(directory, body):
    path = Path(directory) / "invoices.csv"
    path.write_text(HEADER + body, encoding="utf-8", newline="\n")
    return path


class PipelineTest(unittest.TestCase):
    def test_rows_are_read_with_their_columns(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_csv(directory, 'A-1,Acme,2024-05-02T10:00:00,"1,450.10",paid\n')
            self.assertEqual(load_rows(path), [{"id": "A-1", "customer": "Acme", "issued_at": "2024-05-02T10:00:00", "amount": "1,450.10", "status": "paid"}])

    def test_void_invoices_are_dropped_and_the_rest_sorted(self):
        rows = [
            {"id": "B", "customer": " Birch ", "issued_at": "2024-05-09 08:00:00", "amount": "20.50", "status": "paid"},
            {"id": "V", "customer": "Void", "issued_at": "2024-05-03", "amount": "99.00", "status": "VOID"},
            {"id": "A", "customer": "Acme", "issued_at": "2024-05-02T10:00:00", "amount": "1,450.10", "status": "open"},
        ]
        invoices = normalize(rows)
        self.assertEqual([invoice.id for invoice in invoices], ["A", "B"])
        self.assertEqual(invoices[0].issued_at, datetime(2024, 5, 2, 10, 0))
        self.assertEqual(float(invoices[0].amount), 1450.10)
        self.assertEqual(invoices[1].customer, "Birch")

    def test_amounts_are_printed_with_cents_and_thousands(self):
        self.assertEqual(format_amount(1234.5), "1,234.50")
        self.assertEqual(format_amount(0.1 + 0.2), "0.30")

    def test_report_of_a_mid_month_file(self):
        body = (
            "A-1,Acme,2024-05-02T10:00:00,100.10,paid\n"
            "A-2,Birch,2024-05-20 12:30:00,200.20,paid\n"
            "A-3,Acme,2024-06-15,50.00,open\n"
            "A-4,Cobalt,2024-06-16T09:00:00,9.99,void\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            report = build_report(write_csv(directory, body))
        self.assertEqual(report, "Monthly totals\n2024-05  n=2  300.30\n2024-06  n=1  50.00\nTotal  n=3  350.30")


if __name__ == "__main__":
    unittest.main()
