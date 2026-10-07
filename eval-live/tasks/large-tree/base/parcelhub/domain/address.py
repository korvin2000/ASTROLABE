from dataclasses import dataclass

from .errors import ParcelError


@dataclass(frozen=True)
class Address:
    country: str
    postcode: str = ""

    def __post_init__(self):
        if len(self.country) != 2 or not self.country.isalpha() or not self.country.isupper():
            raise ParcelError("country must be a two-letter upper-case code: %r" % self.country)
