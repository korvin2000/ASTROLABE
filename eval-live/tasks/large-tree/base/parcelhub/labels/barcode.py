def check_digit(text):
    """A mod-10 check digit over the character codes of ``text``."""
    return sum(ord(ch) * (3 if i % 2 else 1) for i, ch in enumerate(text)) % 10


def barcode_text(parcel_id):
    return "*%s%d*" % (parcel_id.upper(), check_digit(parcel_id.upper()))
