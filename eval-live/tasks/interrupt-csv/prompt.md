Add a CSV export of the weekly sales report: a function `export_weekly(report, path)` in `reports/export.py` that writes
the `WeeklyReport` to the file `path`. The first line is the header `date`, `category`, `amount`; then one line per
entry of the report, in the order of `report.entries()`, with the date in ISO format (`2026-03-02`) and the amount
with two decimals (`15.50`). Keep the existing tests passing and add a test for the new export.
