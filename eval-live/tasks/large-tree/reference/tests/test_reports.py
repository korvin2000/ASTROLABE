import unittest

from parcelhub.reports import summarize
from parcelhub.reports.exceptions import needing_attention
from parcelhub.reports.summary import render_summary

from support import parcel


class Reports(unittest.TestCase):
    def test_the_week_in_numbers(self):
        week = [parcel(30), parcel(110), parcel(30, 26000), parcel(130, 30000)]
        self.assertEqual(summarize(week), {"parcels": 4, "large": 1, "heavy": 2})
        self.assertEqual(render_summary(summarize(week)), "parcels 4, large 1, heavy 2")

    def test_the_limits_come_from_the_config(self):
        self.assertEqual(summarize([parcel(50)], {"large_over_cm": 40, "heavy_over_kg": 25})["large"], 1)

    def test_the_warehouse_handles_bulky_and_heavy_by_hand(self):
        week = [parcel(30, id="A"), parcel(130, id="B"), parcel(30, 26000, id="C"), parcel(110, id="D")]
        self.assertEqual(needing_attention(week), ["B", "C"])


if __name__ == "__main__":
    unittest.main()
