class ParcelError(ValueError):
    """A parcel that cannot exist: a side or the weight is not positive, or a country is unknown."""
