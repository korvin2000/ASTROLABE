"""Delivery zones: 1 home market, 2 the rest of the EU, 3 everything else."""

from .address import Address

HOME = "DE"
EU = frozenset({"AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"})


def zone_for(address):
    if address.country == HOME:
        return 1
    return 2 if address.country in EU else 3


def is_domestic(address: Address) -> bool:
    return zone_for(address) == 1
