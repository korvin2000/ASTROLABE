"""Formatting of money amounts for the documents the shop prints."""

SYMBOLS = {"USD": "$", "EUR": "€", "GBP": "£"}


def format_amount(cents, currency):
    """`cents` in `currency`: (1250, "EUR") -> "€12.50", (-300, "CHF") -> "-3.00 CHF"."""
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    number = f"{cents // 100}.{cents % 100:02d}"
    symbol = SYMBOLS.get(currency)
    return f"{sign}{symbol}{number}" if symbol else f"{sign}{number} {currency}"
