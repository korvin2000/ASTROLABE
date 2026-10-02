# billing

Monthly totals of an invoice CSV (`id,customer,issued_at,amount,status`).

```
loader.load_rows -> normalize.normalize -> aggregate.monthly_totals / grand_total -> format.format_report
```

`billing.report.build_report(path)` runs the stages and returns the report: one line per month that has invoices,
then a total line over all invoices. `issued_at` is local time (no time zone); an invoice belongs to the calendar
month its `issued_at` falls in, so the monthly figures add up to the total. Void invoices are not counted.

`python repro.py` prints the report for `data/invoices.csv` and checks that the months add up to the total.

Tests: `python -m unittest discover -s tests`.
