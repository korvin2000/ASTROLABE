"""The text listing of the catalog, one page at a time."""

from catalog.paging import page_count, paginate


def render(products, page, per_page=10):
    """A header line `Page <page> of <pages>` followed by one `- <product>` line per product of the page."""
    rows = paginate(products, page, per_page)
    pages = max(page_count(len(products), per_page), 1)
    lines = [f"Page {page} of {pages}"]
    lines += [f"- {product}" for product in rows]
    return "\n".join(lines)
