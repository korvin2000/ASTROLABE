"""Amounts are integer cents everywhere; only the edges of the program format them."""


def format_cents(cents):
    sign = "-" if cents < 0 else ""
    whole, part = divmod(abs(cents), 100)
    return "%s%d.%02d" % (sign, whole, part)


def total(amounts):
    return sum(amounts)
