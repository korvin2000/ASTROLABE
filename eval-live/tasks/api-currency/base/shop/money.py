"""Formatting of money amounts for the documents the shop prints."""


def format_amount(cents):
    """`cents` as a dollar amount: 1250 -> "$12.50", -300 -> "-$3.00"."""
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    return f"{sign}${cents // 100}.{cents % 100:02d}"
