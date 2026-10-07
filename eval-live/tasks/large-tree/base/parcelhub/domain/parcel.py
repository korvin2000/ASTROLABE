from dataclasses import dataclass
from enum import Enum

from .address import Address
from .errors import ParcelError
from .units import BULKY_EDGE_MM, CLASS_EDGES_MM, MM_PER_CM


class SizeClass(Enum):
    S = "S"
    M = "M"
    L = "L"
    XL = "XL"


@dataclass(frozen=True)
class Parcel:
    id: str
    length_cm: float
    width_cm: float
    height_cm: float
    weight_g: int
    destination: Address

    def __post_init__(self):
        if min(self.length_cm, self.width_cm, self.height_cm) <= 0:
            raise ParcelError("every side must be positive")
        if self.weight_g <= 0:
            raise ParcelError("the weight must be positive")

    @property
    def longest_mm(self):
        return round(max(self.length_cm, self.width_cm, self.height_cm) * MM_PER_CM)

    @property
    def volume_cm3(self):
        return self.length_cm * self.width_cm * self.height_cm

    @property
    def size_class(self):
        for size, edge in zip((SizeClass.S, SizeClass.M, SizeClass.L), CLASS_EDGES_MM):
            if self.longest_mm <= edge:
                return size
        return SizeClass.XL

    @property
    def is_bulky(self):
        return self.longest_mm > BULKY_EDGE_MM
