"""An order and its lines."""

from dataclasses import dataclass, field

NEW = "new"
PLACED = "placed"
REJECTED = "rejected"
CANCELLED = "cancelled"
SHIPPED = "shipped"


@dataclass(frozen=True)
class Line:
    sku: str
    qty: int


@dataclass
class Order:
    id: str
    lines: list
    status: str = NEW
    # Why the order was rejected, e.g. the SKU that was short.
    note: str = ""
    history: list = field(default_factory=list)
    # The stock reservations made for this order at checkout.
    reservations: list = field(default_factory=list)

    def move(self, status, note=""):
        self.history.append((self.status, status))
        self.status = status
        self.note = note
