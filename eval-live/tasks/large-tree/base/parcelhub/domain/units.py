"""Units and the size classes parcels are sorted into."""

MM_PER_CM = 10
G_PER_KG = 1000

# A parcel is bulky when its longest side is over this many millimetres: the rate card, the carrier choice, the labels
# and the weekly report all treat it differently from the size class XL up.
BULKY_EDGE_MM = 1000

# Upper edges of the size classes S, M and L in millimetres of the longest side; anything longer is XL.
CLASS_EDGES_MM = (300, 600, BULKY_EDGE_MM)
