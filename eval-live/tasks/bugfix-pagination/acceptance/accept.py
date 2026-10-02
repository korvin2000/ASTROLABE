"""Hidden acceptance of bugfix-pagination: run from the root of a copy of the finished workspace."""

import os
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from catalog.listing import render  # noqa: E402
from catalog.paging import page_count, paginate  # noqa: E402


class PageCount(unittest.TestCase):
    def test_partly_filled_last_page_counts(self):
        self.assertEqual(page_count(23, 10), 3)
        self.assertEqual(page_count(1, 10), 1)
        self.assertEqual(page_count(11, 5), 3)

    def test_full_pages_and_empty(self):
        self.assertEqual(page_count(20, 10), 2)
        self.assertEqual(page_count(0, 10), 0)

    def test_per_page_must_be_positive(self):
        with self.assertRaises(ValueError):
            page_count(5, 0)


class Paginate(unittest.TestCase):
    items = [f"product-{n}" for n in range(1, 24)]

    def test_last_partly_filled_page(self):
        self.assertEqual(paginate(self.items, 3), self.items[20:23])

    def test_every_page_in_order(self):
        pages = [paginate(self.items, p) for p in (1, 2, 3)]
        self.assertEqual(sum(pages, []), self.items)

    def test_small_list_has_one_page(self):
        self.assertEqual(paginate(self.items[:5], 1), self.items[:5])
        with self.assertRaises(ValueError):
            paginate(self.items[:5], 2)

    def test_page_past_the_end_is_refused(self):
        with self.assertRaises(ValueError):
            paginate(self.items, 4)
        with self.assertRaises(ValueError):
            paginate(self.items[:20], 3)
        with self.assertRaises(ValueError):
            paginate(self.items, 0)

    def test_empty_list_shows_an_empty_first_page(self):
        self.assertEqual(paginate([], 1), [])


class Listing(unittest.TestCase):
    products = [f"product-{n}" for n in range(1, 24)]

    def test_header_counts_the_partly_filled_page(self):
        self.assertEqual(render(self.products, 1).splitlines()[0], "Page 1 of 3")
        self.assertEqual(render(self.products, 3).splitlines(), ["Page 3 of 3", "- product-21", "- product-22", "- product-23"])

    def test_empty_listing(self):
        self.assertEqual(render([], 1), "Page 1 of 1")

    def test_reproducer_succeeds(self):
        result = subprocess.run([sys.executable, "repro.py"], cwd=ROOT, capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
