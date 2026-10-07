from .hs_codes import lookup

# Customs authority rule for parcels leaving the EU: a parcel with a side over 100 cm needs the extra form C-17.
LONG_PARCEL_CM = 100


def needs_extra_form(parcel):
    return max(parcel.length_cm, parcel.width_cm, parcel.height_cm) > LONG_PARCEL_CM


def declaration_lines(parcel, kind, value_cents):
    lines = ["PARCEL %s" % parcel.id, "HS %s" % lookup(kind), "VALUE %d" % value_cents]
    if needs_extra_form(parcel):
        lines.append("FORM C-17")
    return lines
