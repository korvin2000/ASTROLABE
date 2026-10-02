"""Stock items."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Item:
    sku: str
    on_hand: int
    reorder_point: int
    target: int

    def __post_init__(self):
        if not self.sku:
            raise ValueError("an item needs a sku")
        if self.on_hand < 0 or self.reorder_point < 0:
            raise ValueError("stock levels cannot be negative")
        if self.target <= self.reorder_point:
            raise ValueError("the target level must be above the reorder point")
