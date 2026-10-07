Two independent additions to the exports of `spend`; do both.

1. **CSV export.** A format `csv`, chosen by the extension `.csv`, described as `CSV: date, category, note, amount`.
   The header row is `date,category,note,amount`, then one row per expense in ledger order, the amount with two
   decimals (`-4.50`) and no total row. Fields are quoted the way Python's `csv` module does by default (a note with a
   comma or a double quote is quoted); rows end with `\n`.
2. **JSON export.** A format `json`, chosen by the extension `.json`, described as `JSON document with the total`. The
   document is `{"expenses": [{"date": …, "category": …, "note": …, "amount_cents": …}, …], "total_cents": …}`, the
   expenses in ledger order, indented by two spaces, non-ASCII text kept as it is, ending with a newline.

Both formats are registered in the format registry (`spend/export/registry.py`), so `spend export --format` accepts
them, `--output` picks them by extension and `spend formats` lists all four formats. The existing `text` and
`markdown` exports stay as they are. Add tests for both new formats.
