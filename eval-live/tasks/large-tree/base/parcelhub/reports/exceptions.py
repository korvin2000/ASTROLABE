from ..domain.units import G_PER_KG

HEAVY_KG = 25


def needing_attention(parcels):
    """Ids of the parcels the warehouse handles by hand: the bulky ones and the heavy ones."""
    return [p.id for p in parcels if p.is_bulky or p.weight_g > HEAVY_KG * G_PER_KG]
