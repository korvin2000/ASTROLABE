from parcelhub.domain.address import Address
from parcelhub.domain.parcel import Parcel


def parcel(longest_cm=30, weight_g=1000, country="DE", id="P1"):
    return Parcel(id, longest_cm, 20, 10, weight_g, Address(country, "10115"))
