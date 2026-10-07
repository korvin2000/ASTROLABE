import unittest

from depot.orders import OrderBook
from depot.stock import Inventory
from depot.web.handlers import Shop


class WebTest(unittest.TestCase):
    def setUp(self):
        self.shop = Shop(Inventory({"A-1": 5, "B-2": 1}), OrderBook())

    def test_place_and_cancel(self):
        status, body = self.shop.handle("POST", "/orders", {"lines": [{"sku": "A-1", "qty": 2}]})
        self.assertEqual((status, body["status"]), (201, "placed"))
        self.assertEqual(self.shop.handle("GET", "/stock/A-1"), (200, {"sku": "A-1", "available": 3}))
        status, body = self.shop.handle("POST", "/orders/" + body["id"] + "/cancel")
        self.assertEqual((status, body["status"]), (200, "cancelled"))
        self.assertEqual(self.shop.handle("GET", "/stock/A-1")[1]["available"], 5)

    def test_out_of_stock(self):
        status, body = self.shop.handle("POST", "/orders", {"lines": [{"sku": "B-2", "qty": 3}]})
        self.assertEqual(status, 409)
        self.assertEqual((body["error"], body["sku"]), ("out_of_stock", "B-2"))


if __name__ == "__main__":
    unittest.main()
