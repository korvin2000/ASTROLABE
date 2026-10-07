"""Hidden acceptance of api-callers: run from the root of a copy of the finished workspace."""

import dataclasses
import inspect
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import depot.stock as stock  # noqa: E402
from depot.admin.commands import hold, unhold  # noqa: E402
from depot.orders import OrderBook  # noqa: E402
from depot.orders.cancel import cancel  # noqa: E402
from depot.orders.checkout import checkout  # noqa: E402
from depot.orders.fulfil import fulfil  # noqa: E402
from depot.reports.availability import morning_report  # noqa: E402
from depot.web.handlers import Shop  # noqa: E402

Inventory = stock.Inventory


class StockApi(unittest.TestCase):
    def test_exports(self):
        for name in ("Inventory", "Reservation", "OutOfStock"):
            self.assertTrue(hasattr(stock, name), f"depot.stock exports {name}")
        self.assertTrue(dataclasses.is_dataclass(stock.Reservation), "Reservation is a dataclass")
        self.assertTrue({f.name for f in dataclasses.fields(stock.Reservation)} >= {"id", "sku", "qty", "order_id"})

    def test_order_id_is_keyword_only_and_required(self):
        parameter = inspect.signature(Inventory.reserve).parameters.get("order_id")
        self.assertIsNotNone(parameter, "reserve has an order_id parameter")
        self.assertIs(parameter.kind, inspect.Parameter.KEYWORD_ONLY)
        self.assertIs(parameter.default, inspect.Parameter.empty)
        inv = Inventory({"A-1": 5})
        with self.assertRaises(TypeError):
            inv.reserve("A-1", 1)

    def test_reservations(self):
        inv = Inventory({"A-1": 5, "B-2": 3})
        first = inv.reserve("A-1", 2, order_id="O-7")
        second = inv.reserve("B-2", 1, order_id="desk")
        self.assertEqual((first.id, first.sku, first.qty, first.order_id), ("R-1", "A-1", 2, "O-7"))
        self.assertEqual((second.id, second.order_id), ("R-2", "desk"))
        with self.assertRaises(dataclasses.FrozenInstanceError):
            first.qty = 9
        self.assertEqual((inv.available("A-1"), inv.reserved("A-1")), (3, 2))
        self.assertEqual(inv.held("R-1"), first)
        inv.release(first)
        self.assertIsNone(inv.held("R-1"))
        self.assertEqual(inv.available("A-1"), 5)
        with self.assertRaises(ValueError):
            inv.release(first)
        inv.ship(second)
        self.assertEqual((inv.on_hand("B-2"), inv.available("B-2")), (2, 2))
        with self.assertRaises(ValueError):
            inv.ship(second)
        self.assertEqual(inv.reserve("A-1", 1, order_id="O-8").id, "R-3")

    def test_out_of_stock(self):
        inv = Inventory({"A-1": 2})
        inv.reserve("A-1", 1, order_id="O-1")
        with self.assertRaises(stock.OutOfStock) as raised:
            inv.reserve("A-1", 4, order_id="O-2")
        self.assertEqual((raised.exception.sku, raised.exception.requested, raised.exception.available), ("A-1", 4, 1))
        self.assertEqual(inv.available("A-1"), 1)

    def test_old_calls_are_gone(self):
        inv = Inventory({"A-1": 5})
        inv.reserve("A-1", 2, order_id="O-1")
        with self.assertRaises((TypeError, AttributeError)):
            inv.release("A-1", 2)
        with self.assertRaises((TypeError, AttributeError)):
            inv.ship("A-1", 2)


class Orders(unittest.TestCase):
    def setUp(self):
        self.inv = Inventory({"A-1": 5, "B-2": 1, "C-3": 4})
        self.book = OrderBook()

    def test_checkout_keeps_the_reservations(self):
        order = self.book.create([("A-1", 2), ("C-3", 1)])
        self.assertIs(checkout(self.inv, order), True)
        self.assertEqual(order.status, "placed")
        self.assertEqual([(r.sku, r.qty, r.order_id) for r in order.reservations], [("A-1", 2, order.id), ("C-3", 1, order.id)])

    def test_short_order_leaves_nothing_reserved(self):
        order = self.book.create([("A-1", 2), ("C-3", 3), ("B-2", 2)])
        self.assertIs(checkout(self.inv, order), False)
        self.assertEqual((order.status, order.note), ("rejected", "short of B-2"))
        self.assertEqual([self.inv.available(s) for s in ("A-1", "B-2", "C-3")], [5, 1, 4], "a rejected order leaks no reservation")
        self.assertEqual(list(order.reservations), [])

    def test_cancel_releases_exactly_the_order_reservations(self):
        desk = self.inv.reserve("A-1", 1, order_id="desk")
        order = self.book.create([("A-1", 2)])
        checkout(self.inv, order)
        cancel(self.inv, order)
        self.assertEqual(order.status, "cancelled")
        self.assertEqual(self.inv.held(desk.id), desk, "the desk reservation is still held")
        self.assertEqual(self.inv.available("A-1"), 4)

    def test_fulfil_ships_exactly_the_order_reservations(self):
        desk = self.inv.reserve("C-3", 2, order_id="desk")
        order = self.book.create([("C-3", 1), ("A-1", 1)])
        checkout(self.inv, order)
        fulfil(self.inv, order)
        self.assertEqual(order.status, "shipped")
        self.assertEqual((self.inv.on_hand("C-3"), self.inv.reserved("C-3"), self.inv.on_hand("A-1")), (3, 2, 4))
        self.assertEqual(self.inv.held(desk.id), desk)


class ShopAnswers(unittest.TestCase):
    def test_short_answer_gives_the_available_units(self):
        inv = Inventory({"A-1": 5, "B-2": 3})
        inv.reserve("B-2", 1, order_id="desk")
        shop = Shop(inv, OrderBook())
        status, body = shop.handle("POST", "/orders", {"lines": [{"sku": "A-1", "qty": 1}, {"sku": "B-2", "qty": 3}]})
        self.assertEqual(status, 409)
        self.assertEqual((body["error"], body["sku"], body["available"]), ("out_of_stock", "B-2", 2))
        self.assertEqual(shop.handle("GET", "/stock/A-1"), (200, {"sku": "A-1", "available": 5}))

    def test_place_cancel_round_trip(self):
        shop = Shop(Inventory({"A-1": 5}), OrderBook())
        status, body = shop.handle("POST", "/orders", {"lines": [{"sku": "A-1", "qty": 2}]})
        self.assertEqual((status, body["status"]), (201, "placed"))
        status, body = shop.handle("POST", "/orders/" + body["id"] + "/cancel")
        self.assertEqual((status, body["status"]), (200, "cancelled"))
        self.assertEqual(shop.handle("GET", "/stock/A-1")[1]["available"], 5)


class Desk(unittest.TestCase):
    def test_hold_and_unhold_by_id(self):
        inv = Inventory({"A-1": 2, "B-2": 4})
        inv.reserve("B-2", 1, order_id="O-1")
        self.assertEqual(hold(inv, "A-1", 2), "held R-2: 2 x A-1")
        self.assertEqual(inv.held("R-2").order_id, "desk")
        self.assertEqual(hold(inv, "A-1", 1), "not enough A-1: 0 available")
        self.assertEqual(unhold(inv, "R-2"), "released R-2: 2 x A-1")
        self.assertEqual(unhold(inv, "R-9"), "no reservation R-9")
        self.assertEqual(inv.available("A-1"), 2)
        self.assertEqual(inv.held("R-1").order_id, "O-1")

    def test_report_unchanged(self):
        inv = Inventory({"A-1": 5, "B-2": 1})
        inv.reserve("A-1", 4, order_id="O-1")
        self.assertEqual(morning_report(inv), "A-1: on hand 5, reserved 4\nB-2: on hand 1, reserved 0\nLow: A-1 (1), B-2 (1)")


if __name__ == "__main__":
    unittest.main(verbosity=1)
