import sys

from ..domain.address import Address
from ..domain.parcel import Parcel
from .commands import describe, quote_text


def parse_parcel(sizes, weight_g, country):
    length, width, height = (float(part) for part in sizes.split("x"))
    return Parcel("CLI", length, width, height, int(weight_g), Address(country))


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 4 or argv[0] not in ("quote", "describe"):
        print("usage: parcelhub quote|describe LxWxH_CM WEIGHT_G COUNTRY")
        return 2
    parcel = parse_parcel(*argv[1:])
    print(quote_text(parcel) if argv[0] == "quote" else describe(parcel))
    return 0


if __name__ == "__main__":
    sys.exit(main())
