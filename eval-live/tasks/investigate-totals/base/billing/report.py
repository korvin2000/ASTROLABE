"""The four stages in a row."""

from billing.aggregate import grand_total, monthly_totals
from billing.format import format_report
from billing.loader import load_rows
from billing.normalize import normalize


def build_report(path):
    """The monthly report of an invoice CSV file, as text."""
    invoices = normalize(load_rows(path))
    return format_report(monthly_totals(invoices), len(invoices), grand_total(invoices))
