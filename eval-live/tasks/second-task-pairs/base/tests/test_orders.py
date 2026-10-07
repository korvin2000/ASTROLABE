import unittest

from depot.orders import OrderBook
from depot.orders.cancel import cancel
from depot.orders.checkout import checkout
from depot.orders.fulfil import fulfil
from depot.stock import Inventory


class OrdersTest(unittest.TestCase):
    def setUp(self):
        self.inv = Inventory({"A-1": 5, "B-2": 1})
        self.book = OrderBook()

    def test_checkout_reserves_every_line(self):
        order = self.book.create([("A-1", 2), ("B-2", 1)])
        self.assertTrue(checkout(self.inv, order))
        self.assertEqual(order.status, "placed")
        self.assertEqual([(r.sku, r.order_id) for r in order.reservations], [("A-1", order.id), ("B-2", order.id)])
        self.assertEqual((self.inv.available("A-1"), self.inv.available("B-2")), (3, 0))

    def test_short_order_is_rejected_whole(self):
        order = self.book.create([("A-1", 2), ("B-2", 2)])
        self.assertFalse(checkout(self.inv, order))
        self.assertEqual((order.status, order.note), ("rejected", "short of B-2"))
        self.assertEqual(order.reservations, [])
        self.assertEqual(self.inv.available("A-1"), 5)

    def test_cancel_and_fulfil(self):
        first = self.book.create([("A-1", 2)])
        second = self.book.create([("A-1", 1)])
        checkout(self.inv, first)
        checkout(self.inv, second)
        cancel(self.inv, first)
        fulfil(self.inv, second)
        self.assertEqual((self.inv.on_hand("A-1"), self.inv.available("A-1")), (4, 4))


if __name__ == "__main__":
    unittest.main()
