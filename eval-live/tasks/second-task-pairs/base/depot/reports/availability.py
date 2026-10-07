"""The morning availability report."""


def low_stock(inventory, threshold):
    """SKUs whose available units are at or below `threshold`, as `(sku, available)` sorted by SKU."""
    return [(sku, inventory.available(sku)) for sku in inventory.skus() if inventory.available(sku) <= threshold]


def morning_report(inventory, threshold=2):
    lines = [f"{sku}: on hand {inventory.on_hand(sku)}, reserved {inventory.reserved(sku)}" for sku in inventory.skus()]
    low = low_stock(inventory, threshold)
    lines.append("Low: " + (", ".join(f"{sku} ({n})" for sku, n in low) if low else "none"))
    return "\n".join(lines)
