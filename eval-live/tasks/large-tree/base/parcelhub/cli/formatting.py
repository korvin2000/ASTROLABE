from ..domain.money import format_cents


def flags(parcel):
    """One letter per property worth a glance in a listing: B bulky, H heavy (over 25 kg)."""
    return ("B" if parcel.is_bulky else "") + ("H" if parcel.weight_g > 25000 else "")


def money(cents):
    return format_cents(cents) + " EUR"
