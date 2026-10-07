import math

VAN_VOLUME_CM3 = 6_000_000
PACKING_EFFICIENCY = 0.6


def vans_needed(parcels):
    """Vans for a set of parcels by volume alone, at 60 percent packing efficiency."""
    volume = sum(parcel.volume_cm3 for parcel in parcels)
    return math.ceil(volume / (VAN_VOLUME_CM3 * PACKING_EFFICIENCY)) if volume else 0
