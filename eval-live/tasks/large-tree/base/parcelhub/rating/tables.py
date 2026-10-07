"""The rate card's tables: base price in cents by zone and weight band."""

# (upper edge of the band in grams, or None for the last band), price per zone 1, 2, 3
WEIGHT_BANDS = (
    (500, (390, 690, 1290)),
    (2000, (490, 890, 1790)),
    (10000, (790, 1490, 2990)),
    (30000, (1490, 2490, 4990)),
    (None, (2990, 4990, 8990)),
)
