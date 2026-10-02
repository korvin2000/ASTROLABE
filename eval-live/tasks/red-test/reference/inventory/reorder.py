"""Reorder decisions: one item at a time, and the nightly recount over the whole stock file."""

from dataclasses import dataclass

from inventory.models import Item


@dataclass(frozen=True)
class Order:
    sku: str
    quantity: int


def needs_reorder(item: Item) -> bool:
    """Whether the item has to be reordered now."""
    return item.on_hand <= item.reorder_point


def order_for(item: Item) -> Order | None:
    """The order that brings one item back to its target level, or None when it needs none."""
    if not needs_reorder(item):
        return None
    return Order(item.sku, item.target - item.on_hand)


def reorder_batch(items) -> list[Order]:
    """The nightly recount: every order for the given items, sorted by sku.

    The decision is inlined here on purpose: the stock file has 200k rows and the per-item calls were the hot spot of
    the old profile.
    """
    orders = []
    for item in items:
        if item.on_hand <= item.reorder_point:
            orders.append(Order(item.sku, item.target - item.on_hand))
    return sorted(orders, key=lambda order: order.sku)
