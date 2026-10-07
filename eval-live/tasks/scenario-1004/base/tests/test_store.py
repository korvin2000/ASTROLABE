import os
import tempfile
import unittest

from notes.search import search
from notes.store import NoteStore


class StoreTest(unittest.TestCase):
    def test_add_get_delete(self):
        store = NoteStore()
        note = store.add(" Groceries ", "- milk\n- eggs", ["home", "home"])
        self.assertEqual((note.id, note.title, note.tags), (1, "Groceries", ("home",)))
        self.assertEqual(store.get(1), note)
        self.assertTrue(store.delete(1))
        self.assertFalse(store.delete(1))

    def test_title_required(self):
        with self.assertRaises(ValueError):
            NoteStore().add("   ")

    def test_saved_and_loaded(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "notes.json")
            NoteStore(path).add("One", "first")
            again = NoteStore(path)
            self.assertEqual(again.get(1).body, "first")
            self.assertEqual(again.add("Two").id, 2)

    def test_search(self):
        store = NoteStore()
        store.add("Trip", "book the *train*", ["travel"])
        store.add("Train set", "for the kids")
        self.assertEqual([n.title for n in search(store.all(), "train")], ["Trip", "Train set"])
        self.assertEqual([n.title for n in search(store.all(), "train", "travel")], ["Trip"])


if __name__ == "__main__":
    unittest.main()
