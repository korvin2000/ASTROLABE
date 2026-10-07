"""Operator commands: each takes the inventory and returns the line the desk prints."""

from depot.stock import OutOfStock

DESK = "desk"


def hold(inventory, sku, qty):
    """Puts units aside by hand (a phone order, a damaged-goods check)."""
    try:
        reservation = inventory.reserve(sku, qty, order_id=DESK)
    except OutOfStock as short:
        return f"not enough {sku}: {short.available} available"
    return f"held {reservation.id}: {qty} x {sku}"


def unhold(inventory, reservation_id):
    """Gives units put aside by hand back, by the id `hold` printed."""
    reservation = inventory.held(reservation_id)
    if reservation is None:
        return f"no reservation {reservation_id}"
    inventory.release(reservation)
    return f"released {reservation.id}: {reservation.qty} x {reservation.sku}"


def holds(inventory):
    """What the desk has put aside, one reservation per line."""
    held = inventory.reservations(DESK)
    if not held:
        return "no holds"
    return "\n".join(f"{r.id}: {r.qty} x {r.sku}" for r in held)


def receive(inventory, sku, qty):
    inventory.receive(sku, qty)
    return f"received {qty} x {sku}, {inventory.available(sku)} available"
