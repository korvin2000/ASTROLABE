from dataclasses import dataclass


@dataclass(frozen=True)
class Carrier:
    name: str
    max_weight_g: int
    max_edge_mm: int

    def fits(self, parcel):
        return parcel.weight_g <= self.max_weight_g and parcel.longest_mm <= self.max_edge_mm
