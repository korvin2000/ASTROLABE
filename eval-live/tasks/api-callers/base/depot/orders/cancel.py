"""Cancelling a placed order gives its units back."""

from depot.orders.model import CANCELLED, PLACED


def cancel(inventory, order):
    if order.status != PLACED:
        raise ValueError(f"order {order.id} is {order.status}; only a placed order can be cancelled")
    for line in order.lines:
        inventory.release(line.sku, line.qty)
    order.move(CANCELLED)
