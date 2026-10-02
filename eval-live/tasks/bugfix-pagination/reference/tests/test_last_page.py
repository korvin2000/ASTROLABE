import unittest

from catalog.listing import render
from catalog.paging import page_count, paginate


class LastPageTest(unittest.TestCase):
    def test_partly_filled_last_page_opens(self):
        products = [f"product-{n}" for n in range(1, 24)]
        self.assertEqual(page_count(23, 10), 3)
        self.assertEqual(paginate(products, 3), products[20:])
        self.assertEqual(render(products, 3).splitlines()[0], "Page 3 of 3")


if __name__ == "__main__":
    unittest.main()
