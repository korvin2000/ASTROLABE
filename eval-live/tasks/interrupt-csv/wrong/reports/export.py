"""CSV exports of the reports."""

import csv

from reports.weekly import format_cents

WEEKLY_HEADER = ["date", "category", "amount"]


def export_rows(rows, path):
    """Writes `rows` (lists of strings) to the file `path` as comma-separated CSV, one row per line."""
    with open(path, "w", newline="", encoding="utf-8") as out:
        csv.writer(out).writerows(rows)


def export_weekly(report, path):
    """Writes the weekly `report` to `path` as CSV: a header, then one row per entry with an ISO date."""
    rows = [WEEKLY_HEADER] + [[e.day.isoformat(), e.category, format_cents(e.amount_cents)] for e in report.entries()]
    export_rows(rows, path)
