from ..carriers.selection import choose
from ..rating.quote import quote
from .formatting import flags, money


def describe(parcel):
    return "%s  %s  %s  %s" % (parcel.id, parcel.size_class.value, choose(parcel), flags(parcel) or "-")


def quote_text(parcel, loyalty_years=0):
    result = quote(parcel, loyalty_years)
    lines = ["%-8s %s" % (name, money(cents)) for name, cents in result.lines.items()]
    lines.append("%-8s %s" % ("total", money(result.total_cents)))
    return "\n".join(lines)
