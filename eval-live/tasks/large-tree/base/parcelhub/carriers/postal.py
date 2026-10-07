from .base import Carrier

# The postal service's own limits, from our contract with it: letters-and-small-parcels product, 2 kg, 100 cm.
POSTAL = Carrier("postal", max_weight_g=2000, max_edge_mm=1000)


def fits(parcel):
    return POSTAL.fits(parcel)
