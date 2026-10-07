import unittest

from parcelhub.labels import render_label
from parcelhub.labels.warnings import warnings_for

from support import parcel


class Labels(unittest.TestCase):
    def test_a_large_item_is_marked(self):
        self.assertEqual(warnings_for(parcel(130, 1000)), ["LARGE ITEM - NO STACKING"])
        self.assertEqual(warnings_for(parcel(110, 1000)), [])

    def test_a_heavy_item_is_marked(self):
        self.assertEqual(warnings_for(parcel(30, 26000)), ["HEAVY - TWO PERSON LIFT"])

    def test_an_ordinary_parcel_has_no_notes(self):
        self.assertEqual(warnings_for(parcel(30, 1000)), [])

    def test_the_label_carries_the_route_and_the_notes(self):
        text = render_label(parcel(130, 1000), "freight")
        self.assertIn("VIA FREIGHT", text)
        self.assertIn("LARGE ITEM", text)
        self.assertTrue(all(len(line) == 40 for line in text.splitlines()))


if __name__ == "__main__":
    unittest.main()
