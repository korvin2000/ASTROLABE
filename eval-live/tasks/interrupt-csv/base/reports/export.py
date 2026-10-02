"""CSV exports of the reports."""

import csv


def export_rows(rows, path):
    """Writes `rows` (lists of strings) to the file `path` as comma-separated CSV, one row per line."""
    with open(path, "w", newline="", encoding="utf-8") as out:
        csv.writer(out).writerows(rows)
