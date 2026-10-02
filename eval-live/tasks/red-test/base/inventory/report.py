"""The morning reorder report."""

from inventory.reorder import reorder_batch


def reorder_report(items) -> str:
    orders = reorder_batch(items)
    if not orders:
        return "Nothing to reorder"
    return "\n".join(["Reorder"] + [f"{order.sku}: {order.quantity}" for order in orders])
