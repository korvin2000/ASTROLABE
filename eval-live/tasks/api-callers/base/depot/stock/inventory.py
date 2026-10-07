"""The stock of the warehouse: units on hand per SKU and the units reserved against them."""


class Inventory:
    def __init__(self, on_hand=None):
        self._on_hand = {}
        self._reserved = {}
        for sku, qty in (on_hand or {}).items():
            self.receive(sku, qty)

    def receive(self, sku, qty):
        """Adds delivered units to what is on hand."""
        _positive(qty)
        self._on_hand[sku] = self._on_hand.get(sku, 0) + qty

    def on_hand(self, sku):
        return self._on_hand.get(sku, 0)

    def reserved(self, sku):
        return self._reserved.get(sku, 0)

    def available(self, sku):
        """Units that can still be reserved."""
        return self.on_hand(sku) - self.reserved(sku)

    def reserve(self, sku, qty):
        """Reserves `qty` units of `sku`; False (and nothing reserved) when fewer are available."""
        _positive(qty)
        if self.available(sku) < qty:
            return False
        self._reserved[sku] = self.reserved(sku) + qty
        return True

    def release(self, sku, qty):
        """Gives reserved units back."""
        _positive(qty)
        if self.reserved(sku) < qty:
            raise ValueError(f"only {self.reserved(sku)} of {sku} are reserved")
        self._reserved[sku] = self.reserved(sku) - qty

    def ship(self, sku, qty):
        """Reserved units leave the warehouse."""
        self.release(sku, qty)
        self._on_hand[sku] = self.on_hand(sku) - qty

    def skus(self):
        return sorted(self._on_hand)


def _positive(qty):
    if not isinstance(qty, int) or qty <= 0:
        raise ValueError(f"a quantity is a positive whole number, not {qty!r}")
