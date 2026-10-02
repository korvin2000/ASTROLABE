import unittest

from inventory.models import Item
from inventory.reorder import Order, needs_reorder, order_for


def item(on_hand, reorder_point=5, target=20):
    return Item("A-100", on_hand, reorder_point, target)


class ReorderTest(unittest.TestCase):
    def test_plenty_of_stock_needs_no_order(self):
        self.assertFalse(needs_reorder(item(12)))
        self.assertIsNone(order_for(item(12)))

    def test_stock_below_the_point_is_reordered(self):
        self.assertTrue(needs_reorder(item(3)))
        self.assertEqual(order_for(item(3)), Order("A-100", 17))

    def test_stock_at_the_point_is_reordered(self):
        self.assertTrue(needs_reorder(item(5)))
        self.assertEqual(order_for(item(5)), Order("A-100", 15))


if __name__ == "__main__":
    unittest.main()
