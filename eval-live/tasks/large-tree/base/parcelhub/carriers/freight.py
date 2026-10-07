from .base import Carrier

FREIGHT = Carrier("freight", max_weight_g=500000, max_edge_mm=4000)


def fits(parcel):
    return FREIGHT.fits(parcel)
