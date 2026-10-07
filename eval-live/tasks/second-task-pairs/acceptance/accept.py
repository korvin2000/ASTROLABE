"""Hidden acceptance of second-task-pairs (the second task after api-callers): run from the root of a copy of the finished workspace."""

import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from depot.admin.commands import hold, holds, unhold  # noqa: E402
from depot.orders import OrderBook  # noqa: E402
from depot.orders.fulfil import fulfil  # noqa: E402
from depot.reports.availability import morning_report  # noqa: E402
from depot.stock import Inventory  # noqa: E402
from depot.web.handlers import Shop  # noqa: E402


class Listing(unittest.TestCase):
    def test_by_number_all_and_per_order(self):
        inv = Inventory({"A-1": 30, "B-2": 30})
        for n in range(12):
            inv.reserve("A-1" if n % 3 else "B-2", 1, order_id=f"O-{n % 2 + 1}")
        self.assertEqual([r.id for r in inv.reservations()], [f"R-{n}" for n in range(1, 13)])
        self.assertEqual([r.id for r in inv.reservations("O-1")], ["R-1", "R-3", "R-5", "R-7", "R-9", "R-11"])
        self.assertEqual([r.id for r in inv.reservations(order_id="O-2")], ["R-2", "R-4", "R-6", "R-8", "R-10", "R-12"])
        inv.release(inv.held("R-10"))
        self.assertNotIn("R-10", [r.id for r in inv.reservations()])
        self.assertEqual(inv.reservations("O-9"), [])


class ShopReservations(unittest.TestCase):
    def test_order_reservations(self):
        inv = Inventory({"A-1": 5, "B-2": 5})
        inv.reserve("A-1", 1, order_id="desk")
        shop = Shop(inv, OrderBook())
        _, first = shop.handle("POST", "/orders", {"lines": [{"sku": "A-1", "qty": 2}, {"sku": "B-2", "qty": 1}]})
        _, second = shop.handle("POST", "/orders", {"lines": [{"sku": "B-2", "qty": 3}]})
        self.assertEqual(shop.handle("GET", "/orders/" + first["id"] + "/reservations"),
                         (200, [{"id": "R-2", "sku": "A-1", "qty": 2}, {"id": "R-3", "sku": "B-2", "qty": 1}]))
        self.assertEqual(shop.handle("GET", "/orders/" + second["id"] + "/reservations"), (200, [{"id": "R-4", "sku": "B-2", "qty": 3}]))
        shop.handle("POST", "/orders/" + first["id"] + "/cancel")
        self.assertEqual(shop.handle("GET", "/orders/" + first["id"] + "/reservations"), (200, []))
        fulfil(inv, shop.book.get(second["id"]))
        self.assertEqual(shop.handle("GET", "/orders/" + second["id"] + "/reservations"), (200, []))

    def test_unknown_order(self):
        shop = Shop(Inventory({"A-1": 5}), OrderBook())
        self.assertEqual(shop.handle("GET", "/orders/O-41/reservations"), (404, {"error": "no_such_order"}))


class DeskHolds(unittest.TestCase):
    def test_holds_only_the_desk(self):
        inv = Inventory({"A-1": 30, "B-2": 4})
        self.assertEqual(holds(inv), "no holds")
        inv.reserve("A-1", 5, order_id="O-1")
        self.assertEqual(holds(inv), "no holds")
        for _ in range(9):
            inv.reserve("A-1", 1, order_id="O-2")
        hold(inv, "B-2", 2)
        hold(inv, "A-1", 3)
        self.assertEqual(holds(inv), "R-11: 2 x B-2\nR-12: 3 x A-1")
        unhold(inv, "R-11")
        self.assertEqual(holds(inv), "R-12: 3 x A-1")

    def test_report_line(self):
        inv = Inventory({"A-1": 10, "B-2": 1})
        inv.reserve("A-1", 4, order_id="O-1")
        self.assertEqual(morning_report(inv).splitlines()[-1], "Desk holds: none")
        hold(inv, "A-1", 1)
        self.assertEqual(morning_report(inv).splitlines()[-1], "Desk holds: 1 unit in 1 reservation")
        hold(inv, "A-1", 2)
        self.assertEqual(morning_report(inv), "A-1: on hand 10, reserved 7\nB-2: on hand 1, reserved 0\nLow: B-2 (1)\nDesk holds: 3 units in 2 reservations")


if __name__ == "__main__":
    unittest.main(verbosity=1)
