from ..domain.zones import zone_for
from .tables import WEIGHT_BANDS


def base_price_cents(parcel):
    zone = zone_for(parcel.destination)
    for edge, prices in WEIGHT_BANDS:
        if edge is None or parcel.weight_g <= edge:
            return prices[zone - 1]
    raise AssertionError("the last band has no upper edge")
