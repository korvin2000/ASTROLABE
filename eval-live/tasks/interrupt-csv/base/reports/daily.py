"""The daily export the accounting system imports every night."""

from reports.export import export_rows
from reports.weekly import format_cents


def export_daily(sales, day, path):
    """The sales of `day` per category, sorted by category, as CSV with the header `category,amount`."""
    totals = {}
    for sold_on, category, cents in sales:
        if sold_on == day:
            totals[category] = totals.get(category, 0) + cents
    rows = [["category", "amount"]] + [[category, format_cents(totals[category])] for category in sorted(totals)]
    export_rows(rows, path)
