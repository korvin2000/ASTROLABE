"""Hidden acceptance of api-currency: run from the root of a copy of the finished workspace."""

import inspect
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from shop.invoice import render_invoice  # noqa: E402
from shop.money import format_amount  # noqa: E402
from shop.receipt import render_receipt  # noqa: E402
from shop.report import daily_report  # noqa: E402

USD = {"id": "A-1", "currency": "USD", "lines": [{"name": "Tea", "qty": 2, "unit_cents": 350}, {"name": "Cake", "qty": 1, "unit_cents": 425}]}
EUR = {"id": "B-2", "currency": "EUR", "lines": [{"name": "Book", "qty": 1, "unit_cents": 1200}]}
EUR2 = {"id": "B-3", "currency": "EUR", "lines": [{"name": "Pen", "qty": 4, "unit_cents": 175}]}
GBP = {"id": "C-4", "currency": "GBP", "lines": [{"name": "Map", "qty": 1, "unit_cents": 999}]}


class FormatAmount(unittest.TestCase):
    def test_symbols(self):
        self.assertEqual(format_amount(1250, "USD"), "$12.50")
        self.assertEqual(format_amount(1250, "EUR"), "€12.50")
        self.assertEqual(format_amount(1250, "GBP"), "£12.50")
        self.assertEqual(format_amount(5, "EUR"), "€0.05")

    def test_other_codes(self):
        self.assertEqual(format_amount(1250, "CHF"), "12.50 CHF")
        self.assertEqual(format_amount(0, "JPY"), "0.00 JPY")

    def test_negative_amounts(self):
        self.assertEqual(format_amount(-300, "USD"), "-$3.00")
        self.assertEqual(format_amount(-300, "CHF"), "-3.00 CHF")

    def test_currency_is_required(self):
        with self.assertRaises(TypeError):
            format_amount(1250)
        parameter = inspect.signature(format_amount).parameters.get("currency")
        self.assertIsNotNone(parameter, "the parameter is named currency")
        self.assertIs(parameter.default, inspect.Parameter.empty, "currency has no default")


class Documents(unittest.TestCase):
    def test_dollar_documents_are_unchanged(self):
        self.assertEqual(render_invoice(USD), "Invoice A-1\nTea x2: $7.00\nCake x1: $4.25\nTotal: $11.25")
        self.assertEqual(render_receipt(USD), "Receipt A-1: $11.25")

    def test_invoice_in_the_order_currency(self):
        self.assertEqual(render_invoice(EUR), "Invoice B-2\nBook x1: €12.00\nTotal: €12.00")

    def test_receipt_in_the_order_currency(self):
        self.assertEqual(render_receipt(GBP), "Receipt C-4: £9.99")
        self.assertEqual(render_receipt({"id": "D-5", "currency": "CHF", "lines": [{"name": "Knife", "qty": 1, "unit_cents": 3000}]}), "Receipt D-5: 30.00 CHF")


class Report(unittest.TestCase):
    def test_one_total_per_currency_sorted_by_code(self):
        lines = daily_report([USD, EUR, GBP, EUR2]).splitlines()
        self.assertEqual(lines[:5], ["Daily report", "A-1: $11.25", "B-2: €12.00", "C-4: £9.99", "B-3: €7.00"])
        self.assertEqual(lines[5:], ["Total EUR: €19.00", "Total GBP: £9.99", "Total USD: $11.25"])

    def test_single_currency(self):
        self.assertEqual(daily_report([EUR, EUR2]).splitlines()[-1], "Total EUR: €19.00")
        self.assertNotIn("Total:", daily_report([USD]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
