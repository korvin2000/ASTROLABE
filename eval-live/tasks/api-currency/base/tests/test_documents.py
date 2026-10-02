import unittest

from shop.invoice import render_invoice
from shop.receipt import render_receipt

ORDER = {"id": "A-1", "currency": "USD", "lines": [{"name": "Tea", "qty": 2, "unit_cents": 350}, {"name": "Cake", "qty": 1, "unit_cents": 425}]}


class DocumentsTest(unittest.TestCase):
    def test_invoice(self):
        self.assertEqual(render_invoice(ORDER), "Invoice A-1\nTea x2: $7.00\nCake x1: $4.25\nTotal: $11.25")

    def test_receipt(self):
        self.assertEqual(render_receipt(ORDER), "Receipt A-1: $11.25")


if __name__ == "__main__":
    unittest.main()
