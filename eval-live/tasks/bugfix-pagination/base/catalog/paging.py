"""Splitting result lists into pages for the catalog listing."""


def page_count(total, per_page):
    """Number of pages needed to show `total` items, `per_page` at a time."""
    if per_page < 1:
        raise ValueError("per_page must be at least 1")
    return total // per_page


def paginate(items, page, per_page=10):
    """The items of the 1-based `page`; a page past the last one raises ValueError."""
    pages = page_count(len(items), per_page)
    if page < 1 or (pages and page > pages):
        raise ValueError(f"page {page} is out of range 1..{pages}")
    start = (page - 1) * per_page
    return items[start:start + per_page]
