Orders carry a `currency` code (see `shop/orders.py`), but every document of the shop prints dollars. Change
`format_amount` in `shop/money.py` to `format_amount(cents, currency)`, with `currency` required (no default):
USD → `$12.50`, EUR → `€12.50`, GBP → `£12.50`, any other code → `12.50 CHF` (the amount, a space, the code). A
negative amount keeps its minus sign in front: `-$3.00`, `-3.00 CHF`.

Update every caller so that the invoice, the receipt and the daily report show each order in its own currency. The
daily report must no longer add up amounts of different currencies: instead of its single `Total:` line it ends with
one line per currency, sorted by currency code, for example `Total EUR: €19.00`. Keep the existing tests passing.
