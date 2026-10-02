import unittest

from inventory.models import Item
from inventory.report import reorder_report


class ReportTest(unittest.TestCase):
    def test_orders_are_listed_by_sku(self):
        items = [Item("B-2", 1, 4, 10), Item("A-1", 0, 2, 6), Item("C-3", 50, 5, 20)]
        self.assertEqual(reorder_report(items), "Reorder\nA-1: 6\nB-2: 9")

    def test_nothing_to_reorder(self):
        self.assertEqual(reorder_report([Item("A-1", 9, 2, 6)]), "Nothing to reorder")


if __name__ == "__main__":
    unittest.main()
