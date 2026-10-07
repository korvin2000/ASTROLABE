# depot

A small warehouse back office: `depot.stock` keeps what is on hand and what is reserved, `depot.orders` checks orders
out against the stock, cancels and fulfils them, `depot.web` answers the shop's JSON calls, `depot.admin` is the
operator's desk and `depot.reports` the morning availability report.

Tests: `python -m unittest discover -s tests`.
