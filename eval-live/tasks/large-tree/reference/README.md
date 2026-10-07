# parcelhub

Everything the shop does with a parcel between the checkout and the van:

| package | what it does |
|---|---|
| `parcelhub.domain` | parcels, addresses, zones, money, units |
| `parcelhub.rating` | the rate card: base price, surcharges, discounts, quotes |
| `parcelhub.carriers` | the carriers, their limits, and which one takes a parcel |
| `parcelhub.labels` | the label that is printed and stuck on the box |
| `parcelhub.customs` | customs declarations and duty |
| `parcelhub.reports` | the weekly report and the list of parcels that need attention |
| `parcelhub.cli` | the `parcelhub` command line |

Size classes: S up to 30 cm on the longest side, M up to 60 cm, L up to 120 cm, XL above. A parcel in class XL is
**bulky**. Run the tests with `python -m unittest discover -s tests`.
