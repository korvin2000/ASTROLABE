"""One expense of the ledger."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Expense:
    date: str
    category: str
    note: str
    # Whole cents; a refund is negative.
    cents: int
