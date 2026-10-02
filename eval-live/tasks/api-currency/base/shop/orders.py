"""Orders: plain dictionaries.

    {"id": "A-1", "currency": "USD", "lines": [{"name": "Tea", "qty": 2, "unit_cents": 350}]}

`currency` is an ISO code such as USD, EUR or GBP; amounts are integer cents in that currency.
"""


def line_cents(line):
    return line["qty"] * line["unit_cents"]


def total_cents(order):
    return sum(line_cents(line) for line in order["lines"])
