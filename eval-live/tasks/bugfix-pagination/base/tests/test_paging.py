import unittest

from catalog.listing import render
from catalog.paging import page_count, paginate


class PagingTest(unittest.TestCase):
    def test_full_pages(self):
        items = list(range(20))
        self.assertEqual(page_count(20, 10), 2)
        self.assertEqual(paginate(items, 1), list(range(10)))
        self.assertEqual(paginate(items, 2), list(range(10, 20)))

    def test_page_before_the_first_is_refused(self):
        with self.assertRaises(ValueError):
            paginate([1, 2, 3], 0)

    def test_per_page_must_be_positive(self):
        with self.assertRaises(ValueError):
            page_count(5, 0)

    def test_header_of_a_full_listing(self):
        products = [f"p{n}" for n in range(20)]
        self.assertEqual(render(products, 2).splitlines()[0], "Page 2 of 2")


if __name__ == "__main__":
    unittest.main()
