"""The shop front's JSON calls, transport-free: `handle(method, path, body)` returns `(status, payload)`."""

import re

from depot.orders.cancel import cancel
from depot.orders.checkout import checkout
from depot.orders.model import PLACED

_ORDER = re.compile(r"^/orders/(O-\d+)$")
_CANCEL = re.compile(r"^/orders/(O-\d+)/cancel$")
_RESERVATIONS = re.compile(r"^/orders/(O-\d+)/reservations$")
_STOCK = re.compile(r"^/stock/([A-Z0-9-]+)$")


class Shop:
    def __init__(self, inventory, book):
        self.inventory = inventory
        self.book = book

    def handle(self, method, path, body=None):
        if method == "POST" and path == "/orders":
            return self._place(body or {})
        match = _CANCEL.match(path)
        if method == "POST" and match:
            return self._cancel(match.group(1))
        match = _RESERVATIONS.match(path)
        if method == "GET" and match:
            order = self.book.get(match.group(1))
            if order is None:
                return 404, {"error": "no_such_order"}
            return 200, [{"id": r.id, "sku": r.sku, "qty": r.qty} for r in self.inventory.reservations(order.id)]
        match = _ORDER.match(path)
        if method == "GET" and match:
            order = self.book.get(match.group(1))
            return (200, _order_json(order)) if order else (404, {"error": "no_such_order"})
        match = _STOCK.match(path)
        if method == "GET" and match:
            sku = match.group(1)
            return 200, {"sku": sku, "available": self.inventory.available(sku)}
        return 404, {"error": "not_found"}

    def _place(self, body):
        lines = body.get("lines")
        if not isinstance(lines, list) or not lines:
            return 400, {"error": "no_lines"}
        try:
            order = self.book.create((line["sku"], int(line["qty"])) for line in lines)
        except (KeyError, TypeError, ValueError):
            return 400, {"error": "bad_line"}
        if not checkout(self.inventory, order):
            short = order.note.removeprefix("short of ")
            return 409, {"error": "out_of_stock", "order": order.id, "sku": short, "available": self.inventory.available(short)}
        return 201, _order_json(order)

    def _cancel(self, order_id):
        order = self.book.get(order_id)
        if order is None:
            return 404, {"error": "no_such_order"}
        if order.status != PLACED:
            return 409, {"error": "not_placed", "status": order.status}
        cancel(self.inventory, order)
        return 200, _order_json(order)


def _order_json(order):
    return {"id": order.id, "status": order.status, "lines": [{"sku": l.sku, "qty": l.qty} for l in order.lines]}
