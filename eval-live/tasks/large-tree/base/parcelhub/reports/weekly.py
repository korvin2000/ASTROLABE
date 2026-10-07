import json
from pathlib import Path

from ..domain.units import G_PER_KG, MM_PER_CM

CONFIG_PATH = Path(__file__).with_name("config.json")


def load_config():
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def summarize(parcels, config=None):
    """Counts for the week: all parcels, the large ones and the heavy ones, by the limits of ``config.json``."""
    config = config or load_config()
    counts = {"parcels": 0, "large": 0, "heavy": 0}
    for parcel in parcels:
        counts["parcels"] += 1
        if parcel.longest_mm / MM_PER_CM > config["large_over_cm"]:
            counts["large"] += 1
        if parcel.weight_g / G_PER_KG > config["heavy_over_kg"]:
            counts["heavy"] += 1
    return counts
