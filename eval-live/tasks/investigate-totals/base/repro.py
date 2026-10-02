"""Reproduces the complaint: the monthly figures of the report do not add up to its total line."""

from pathlib import Path

from billing.aggregate import grand_total, monthly_totals
from billing.loader import load_rows
from billing.normalize import normalize
from billing.report import build_report

DATA = Path(__file__).resolve().parent / "data" / "invoices.csv"

print(build_report(DATA))

invoices = normalize(load_rows(DATA))
by_months = sum(total.amount for total in monthly_totals(invoices))
overall = grand_total(invoices)
print(f"\nthe months add up to {by_months:,.2f}, all invoices to {overall:,.2f}")
if round(by_months - overall, 2) != 0:
    print(f"MISMATCH: the months are off by {by_months - overall:,.2f}")
    raise SystemExit(1)
