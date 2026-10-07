"""Orders by id."""

from depot.orders.model import Line, Order


class OrderBook:
    def __init__(self):
        self._orders = {}
        self._next = 1

    def create(self, lines):
        order = Order(f"O-{self._next}", [Line(sku, qty) for sku, qty in lines])
        self._next += 1
        self._orders[order.id] = order
        return order

    def get(self, order_id):
        return self._orders.get(order_id)

    def all(self):
        return list(self._orders.values())
