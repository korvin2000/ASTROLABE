from .base import Carrier

COURIER = Carrier("courier", max_weight_g=30000, max_edge_mm=1800)


def fits(parcel):
    return COURIER.fits(parcel)
