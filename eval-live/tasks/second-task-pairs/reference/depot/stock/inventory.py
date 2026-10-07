"""The stock of the warehouse: units on hand per SKU and the reservations held against them."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Reservation:
    id: str
    sku: str
    qty: int
    order_id: str


class OutOfStock(Exception):
    def __init__(self, sku, requested, available):
        super().__init__(f"{requested} x {sku} requested, {available} available")
        self.sku = sku
        self.requested = requested
        self.available = available


class Inventory:
    def __init__(self, on_hand=None):
        self._on_hand = {}
        self._held = {}
        self._next = 1
        for sku, qty in (on_hand or {}).items():
            self.receive(sku, qty)

    def receive(self, sku, qty):
        """Adds delivered units to what is on hand."""
        _positive(qty)
        self._on_hand[sku] = self._on_hand.get(sku, 0) + qty

    def on_hand(self, sku):
        return self._on_hand.get(sku, 0)

    def reserved(self, sku):
        return sum(r.qty for r in self._held.values() if r.sku == sku)

    def available(self, sku):
        """Units that can still be reserved."""
        return self.on_hand(sku) - self.reserved(sku)

    def reserve(self, sku, qty, *, order_id):
        """Reserves `qty` units of `sku` for `order_id`; OutOfStock (and nothing reserved) when fewer are available."""
        _positive(qty)
        available = self.available(sku)
        if available < qty:
            raise OutOfStock(sku, qty, available)
        reservation = Reservation(f"R-{self._next}", sku, qty, order_id)
        self._next += 1
        self._held[reservation.id] = reservation
        return reservation

    def held(self, reservation_id):
        """The reservation of that id while it is held, else None."""
        return self._held.get(reservation_id)

    def reservations(self, order_id=None):
        """The held reservations by number (R-2 before R-10): all of them, or those of `order_id`."""
        held = [r for r in self._held.values() if order_id is None or r.order_id == order_id]
        return sorted(held, key=lambda r: int(r.id.split("-", 1)[1]))

    def release(self, reservation):
        """Gives a held reservation back."""
        self._take(reservation)

    def ship(self, reservation):
        """A held reservation leaves the warehouse."""
        self._take(reservation)
        self._on_hand[reservation.sku] = self.on_hand(reservation.sku) - reservation.qty

    def skus(self):
        return sorted(self._on_hand)

    def _take(self, reservation):
        if self._held.get(reservation.id) != reservation:
            raise ValueError(f"reservation {reservation.id} is not held")
        del self._held[reservation.id]


def _positive(qty):
    if not isinstance(qty, int) or qty <= 0:
        raise ValueError(f"a quantity is a positive whole number, not {qty!r}")
