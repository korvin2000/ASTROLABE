"""Stage 1: the raw rows of an invoice CSV."""

import csv


def load_rows(path):
    """The rows of the file as dicts of strings; the header names the columns."""
    with open(path, newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))
