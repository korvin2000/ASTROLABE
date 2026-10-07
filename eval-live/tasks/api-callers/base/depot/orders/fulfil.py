"""Fulfilment: a placed order's reserved units are shipped."""

from depot.orders.model import PLACED, SHIPPED


def fulfil(inventory, order):
    if order.status != PLACED:
        raise ValueError(f"order {order.id} is {order.status}; only a placed order ships")
    for line in order.lines:
        inventory.ship(line.sku, line.qty)
    order.move(SHIPPED)
