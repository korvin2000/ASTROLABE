from ..domain.zones import zone_for
from .barcode import barcode_text
from .layout import box
from .warnings import warnings_for


def render_label(parcel, carrier_name):
    lines = [
        "PARCEL %s" % parcel.id,
        "TO %s %s" % (parcel.destination.country, parcel.destination.postcode),
        "ZONE %d  VIA %s" % (zone_for(parcel.destination), carrier_name.upper()),
        barcode_text(parcel.id),
    ]
    lines += warnings_for(parcel)
    return box(lines)
