from dataclasses import dataclass, field

from .discounts import loyalty_discount_cents
from .rounding import round_up_to_five
from .surcharges import bulky_surcharge_cents, fuel_surcharge_cents, remote_surcharge_cents
from .tariff import base_price_cents


@dataclass(frozen=True)
class Quote:
    lines: dict = field(default_factory=dict)
    total_cents: int = 0


def quote(parcel, loyalty_years=0):
    """The price of sending ``parcel``: one line per component, zero components left out, the total rounded up to 5 cents."""
    base = base_price_cents(parcel)
    lines = {"base": base}
    for name, cents in (("bulky", bulky_surcharge_cents(parcel)), ("remote", remote_surcharge_cents(parcel)), ("fuel", fuel_surcharge_cents(base))):
        if cents:
            lines[name] = cents
    subtotal = sum(lines.values())
    discount = loyalty_discount_cents(subtotal, loyalty_years)
    if discount:
        lines["loyalty"] = -discount
    return Quote(lines, round_up_to_five(subtotal - discount))
