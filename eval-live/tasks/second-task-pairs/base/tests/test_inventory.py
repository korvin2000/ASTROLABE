import unittest

from depot.admin.commands import hold, unhold
from depot.reports.availability import morning_report
from depot.stock import Inventory, OutOfStock


class InventoryTest(unittest.TestCase):
    def test_reserve_and_release(self):
        inv = Inventory({"A-1": 5})
        reservation = inv.reserve("A-1", 3, order_id="O-1")
        self.assertEqual((reservation.sku, reservation.qty, reservation.order_id), ("A-1", 3, "O-1"))
        self.assertEqual(inv.available("A-1"), 2)
        with self.assertRaises(OutOfStock):
            inv.reserve("A-1", 3, order_id="O-2")
        inv.release(reservation)
        self.assertEqual(inv.available("A-1"), 5)

    def test_ship(self):
        inv = Inventory({"A-1": 5})
        inv.ship(inv.reserve("A-1", 2, order_id="O-1"))
        self.assertEqual((inv.on_hand("A-1"), inv.reserved("A-1")), (3, 0))

    def test_desk(self):
        inv = Inventory({"A-1": 2})
        self.assertEqual(hold(inv, "A-1", 2), "held R-1: 2 x A-1")
        self.assertEqual(hold(inv, "A-1", 1), "not enough A-1: 0 available")
        self.assertEqual(unhold(inv, "R-1"), "released R-1: 2 x A-1")

    def test_report(self):
        inv = Inventory({"A-1": 5, "B-2": 1})
        self.assertEqual(morning_report(inv), "A-1: on hand 5, reserved 0\nB-2: on hand 1, reserved 0\nLow: B-2 (1)")


if __name__ == "__main__":
    unittest.main()
