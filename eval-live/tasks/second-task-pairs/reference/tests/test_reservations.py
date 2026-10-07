import unittest

from depot.admin.commands import hold, holds
from depot.orders import OrderBook
from depot.reports.availability import morning_report
from depot.stock import Inventory
from depot.web.handlers import Shop


class ReservationsTest(unittest.TestCase):
    def test_listing(self):
        inv = Inventory({"A-1": 20})
        made = [inv.reserve("A-1", 1, order_id="O-1" if n % 2 else "O-2") for n in range(11)]
        self.assertEqual([r.id for r in inv.reservations()], [r.id for r in made])
        self.assertEqual([r.id for r in inv.reservations("O-2")], ["R-1", "R-3", "R-5", "R-7", "R-9", "R-11"])

    def test_shop(self):
        shop = Shop(Inventory({"A-1": 5}), OrderBook())
        status, body = shop.handle("POST", "/orders", {"lines": [{"sku": "A-1", "qty": 2}]})
        self.assertEqual(shop.handle("GET", "/orders/" + body["id"] + "/reservations"), (200, [{"id": "R-1", "sku": "A-1", "qty": 2}]))
        shop.handle("POST", "/orders/" + body["id"] + "/cancel")
        self.assertEqual(shop.handle("GET", "/orders/" + body["id"] + "/reservations"), (200, []))
        self.assertEqual(shop.handle("GET", "/orders/O-9/reservations")[0], 404)

    def test_desk_and_report(self):
        inv = Inventory({"A-1": 5})
        self.assertEqual(holds(inv), "no holds")
        hold(inv, "A-1", 2)
        inv.reserve("A-1", 1, order_id="O-1")
        self.assertEqual(holds(inv), "R-1: 2 x A-1")
        self.assertTrue(morning_report(inv).endswith("\nDesk holds: 2 units in 1 reservation"))


if __name__ == "__main__":
    unittest.main()
