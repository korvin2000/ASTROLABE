"""Stage 3: totals per month and over everything."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class MonthTotal:
    year: int
    month: int
    count: int
    amount: float


def month_window(year, month):
    """The first instant of the month and the first instant of the next one."""
    start = datetime(year, month, 1)
    end = datetime(year + 1, 1, 1) if month == 12 else datetime(year, month + 1, 1)
    return start, end


def monthly_totals(invoices):
    """One total per month that has invoices, oldest month first."""
    months = sorted({(invoice.issued_at.year, invoice.issued_at.month) for invoice in invoices})
    totals = []
    for year, month in months:
        start, end = month_window(year, month)
        inside = [invoice for invoice in invoices if start <= invoice.issued_at <= end]
        totals.append(MonthTotal(year, month, len(inside), sum(invoice.amount for invoice in inside)))
    return totals


def grand_total(invoices):
    return sum(invoice.amount for invoice in invoices)
