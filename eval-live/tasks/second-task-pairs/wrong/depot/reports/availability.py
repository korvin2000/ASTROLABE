"""The morning availability report."""

from depot.admin.commands import DESK


def low_stock(inventory, threshold):
    """SKUs whose available units are at or below `threshold`, as `(sku, available)` sorted by SKU."""
    return [(sku, inventory.available(sku)) for sku in inventory.skus() if inventory.available(sku) <= threshold]


def morning_report(inventory, threshold=2):
    lines = [f"{sku}: on hand {inventory.on_hand(sku)}, reserved {inventory.reserved(sku)}" for sku in inventory.skus()]
    low = low_stock(inventory, threshold)
    lines.append("Low: " + (", ".join(f"{sku} ({n})" for sku, n in low) if low else "none"))
    lines.append("Desk holds: " + desk_holds(inventory))
    return "\n".join(lines)


def desk_holds(inventory):
    """`3 units in 2 reservations`, counting only what the operator desk holds, or `none`."""
    held = inventory.reservations()
    if not held:
        return "none"
    units = sum(r.qty for r in held)
    return f"{units} unit{'' if units == 1 else 's'} in {len(held)} reservation{'' if len(held) == 1 else 's'}"
