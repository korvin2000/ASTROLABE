LARGE_ITEM_CM = 100
HEAVY_ITEM_G = 25000


def warnings_for(parcel):
    """The handling notes printed on the label, most important first."""
    notes = []
    if max(parcel.length_cm, parcel.width_cm, parcel.height_cm) > LARGE_ITEM_CM:
        notes.append("LARGE ITEM - NO STACKING")
    if parcel.weight_g > HEAVY_ITEM_G:
        notes.append("HEAVY - TWO PERSON LIFT")
    return notes
