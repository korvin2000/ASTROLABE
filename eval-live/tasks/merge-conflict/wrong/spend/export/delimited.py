"""CSV: one row per expense, no total row."""

import csv
import io

from spend.core.money import format_cents


def render(expenses):
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["date", "category", "note", "amount"])
    for e in expenses:
        writer.writerow([e.date, e.category, e.note, format_cents(e.cents)])
    return buffer.getvalue()
