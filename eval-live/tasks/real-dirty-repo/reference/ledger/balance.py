"""Balances of an account's entries: a deposit adds, a withdrawal and a refund subtract."""

KINDS = ("deposit", "withdrawal", "refund")


def balance(entries):
    """The balance of ``entries``, a list of ``(kind, amount)`` pairs with non-negative amounts."""
    total = 0
    for kind, amount in entries:
        if kind not in KINDS:
            raise ValueError(f"unknown entry kind {kind!r}")
        if amount < 0:
            raise ValueError("amounts are never negative")
        if kind == "deposit":
            total += amount
        else:
            total -= amount
    return total
