import unittest

from library.errors import ValidationError
from library.models import parse_book
from library.store import BookStore


class Store(unittest.TestCase):
    def test_ids_are_handed_out_in_order_and_never_reused(self):
        store = BookStore()
        first = store.add("A", "x")
        second = store.add("B", "x")
        self.assertEqual((first.id, second.id), (1, 2))
        self.assertTrue(store.delete(1))
        self.assertEqual(store.add("C", "x").id, 3)

    def test_search_needs_every_tag_and_the_exact_author(self):
        store = BookStore()
        store.add("A", "ann", tags=["x", "y"])
        store.add("B", "ann", tags=["x"])
        store.add("C", "bob", tags=["x", "y"])
        self.assertEqual([b.title for b in store.search(tags=["x", "y"])[0]], ["A", "C"])
        self.assertEqual([b.title for b in store.search(author="ann", tags=["y"])[0]], ["A"])
        page, total = store.search(limit=1, offset=1)
        self.assertEqual(([b.title for b in page], total), (["B"], 3))


class Parsing(unittest.TestCase):
    def test_a_valid_body_is_cleaned(self):
        fields = parse_book({"title": " Dune ", "author": "Herbert", "tags": ["SF", "sf", " Classic "]})
        self.assertEqual(fields, {"title": "Dune", "author": "Herbert", "year": None, "tags": ["classic", "sf"]})

    def test_every_bad_field_is_named(self):
        with self.assertRaises(ValidationError) as caught:
            parse_book({"title": "", "year": "1965", "tags": "x"})
        self.assertEqual(set(caught.exception.fields), {"title", "author", "year", "tags"})


if __name__ == "__main__":
    unittest.main()
