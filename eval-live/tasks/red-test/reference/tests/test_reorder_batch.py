import unittest

from inventory.models import Item
from inventory.reorder import Order, needs_reorder, reorder_batch


class ReorderBatchTest(unittest.TestCase):
    def test_item_at_the_point_is_in_the_batch(self):
        items = [Item("B-2", 6, 5, 20), Item("A-1", 5, 5, 20), Item("C-3", 2, 5, 20)]
        self.assertEqual(reorder_batch(items), [Order("A-1", 15), Order("C-3", 18)])

    def test_batch_agrees_with_the_single_item_check(self):
        for on_hand in range(0, 9):
            item = Item("P-1", on_hand, 4, 10)
            self.assertEqual(needs_reorder(item), bool(reorder_batch([item])))


if __name__ == "__main__":
    unittest.main()
