"""Amounts are whole cents; they are shown with two decimals."""


def format_cents(cents):
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    return f"{sign}{cents // 100}.{cents % 100:02d}"


def parse_amount(text):
    """`12.5`, `12.50`, `-3` → cents."""
    text = text.strip()
    sign = -1 if text.startswith("-") else 1
    whole, _, fraction = text.lstrip("+-").partition(".")
    if not whole.isdigit() or (fraction and not fraction.isdigit()) or len(fraction) > 2:
        raise ValueError(f"not an amount: {text!r}")
    return sign * (int(whole) * 100 + int(fraction.ljust(2, "0") or 0))
