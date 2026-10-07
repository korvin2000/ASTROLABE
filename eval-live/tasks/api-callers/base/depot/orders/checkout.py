"""Checkout: every line of an order is reserved, or none is."""

from depot.orders.model import NEW, PLACED, REJECTED


def checkout(inventory, order):
    """Reserves every line of `order`; on the first short line gives back what was reserved and rejects the order."""
    if order.status != NEW:
        raise ValueError(f"order {order.id} is {order.status}, not new")
    done = []
    for line in order.lines:
        if not inventory.reserve(line.sku, line.qty):
            for earlier in done:
                inventory.release(earlier.sku, earlier.qty)
            order.move(REJECTED, f"short of {line.sku}")
            return False
        done.append(line)
    order.move(PLACED)
    return True
