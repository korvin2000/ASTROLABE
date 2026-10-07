MAX_YEARS = 5
PERCENT_PER_YEAR = 2


def loyalty_discount_cents(subtotal_cents, years):
    """Two percent off per year as a customer, at most five years' worth; the percentage is of the whole subtotal."""
    percent = min(max(years, 0), MAX_YEARS) * PERCENT_PER_YEAR
    return subtotal_cents * percent // 100
