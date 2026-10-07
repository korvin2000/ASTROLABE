"""Checkout: every line of an order is reserved, or none is."""

from depot.orders.model import NEW, PLACED, REJECTED
from depot.stock import OutOfStock


def checkout(inventory, order):
    """Reserves every line of `order`; on the first short line gives back what was reserved and rejects the order."""
    if order.status != NEW:
        raise ValueError(f"order {order.id} is {order.status}, not new")
    done = []
    for line in order.lines:
        try:
            done.append(inventory.reserve(line.sku, line.qty, order_id=order.id))
        except OutOfStock as short:
            for earlier in done:
                inventory.release(earlier)
            order.move(REJECTED, f"short of {short.sku}")
            return False
    order.reservations = done
    order.move(PLACED)
    return True
