"""Checkout: every line of an order is reserved, or none is."""

from depot.orders.model import NEW, PLACED, REJECTED
from depot.stock import OutOfStock


def checkout(inventory, order):
    """Reserves every line of `order`; a short line rejects the order."""
    if order.status != NEW:
        raise ValueError(f"order {order.id} is {order.status}, not new")
    try:
        order.reservations = [inventory.reserve(line.sku, line.qty, order_id=order.id) for line in order.lines]
    except OutOfStock as short:
        order.move(REJECTED, f"short of {short.sku}")
        return False
    order.move(PLACED)
    return True
