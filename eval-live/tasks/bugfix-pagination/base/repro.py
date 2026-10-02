"""Reproduces the reported bug: the last, partly filled page of the catalog cannot be opened."""

from catalog.listing import render

products = [f"product-{n}" for n in range(1, 24)]  # 23 products, 10 per page

try:
    print(render(products, 3))
except ValueError as error:
    print("BUG:", error)
    raise SystemExit(1)
