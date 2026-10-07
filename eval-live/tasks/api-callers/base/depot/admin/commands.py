"""Operator commands: each takes the inventory and returns the line the desk prints."""


def hold(inventory, sku, qty):
    """Puts units aside by hand (a phone order, a damaged-goods check)."""
    if inventory.reserve(sku, qty):
        return f"held {qty} x {sku}"
    return f"not enough {sku}: {inventory.available(sku)} available"


def unhold(inventory, sku, qty):
    """Gives units put aside by hand back."""
    inventory.release(sku, qty)
    return f"released {qty} x {sku}"


def receive(inventory, sku, qty):
    inventory.receive(sku, qty)
    return f"received {qty} x {sku}, {inventory.available(sku)} available"
