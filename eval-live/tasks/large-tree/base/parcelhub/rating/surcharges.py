from ..domain.zones import zone_for

BULKY_SURCHARGE_CENTS = 2500
REMOTE_SURCHARGE_CENTS = 400
FUEL_RATE = 0.07


def bulky_surcharge_cents(parcel):
    return BULKY_SURCHARGE_CENTS if parcel.is_bulky else 0


def remote_surcharge_cents(parcel):
    return REMOTE_SURCHARGE_CENTS if zone_for(parcel.destination) == 3 else 0


def fuel_surcharge_cents(base_cents):
    return round(base_cents * FUEL_RATE)
