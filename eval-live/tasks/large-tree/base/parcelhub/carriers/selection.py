from . import courier, postal


def choose(parcel):
    """The carrier that takes ``parcel``: freight for the very large, the postal service for what fits its limits, else a courier."""
    if parcel.longest_mm > 1000:
        return "freight"
    if postal.fits(parcel):
        return "postal"
    return "courier"


def plan(parcels):
    """The parcels grouped by the carrier that takes them, in the order given."""
    groups = {}
    for parcel in parcels:
        groups.setdefault(choose(parcel), []).append(parcel)
    return groups


def courier_fits(parcel):
    return courier.fits(parcel)
