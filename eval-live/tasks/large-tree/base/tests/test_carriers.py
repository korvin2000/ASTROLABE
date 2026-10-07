import unittest

from parcelhub.carriers.capacity import vans_needed
from parcelhub.carriers.selection import choose, plan

from support import parcel


class Choice(unittest.TestCase):
    def test_a_small_light_parcel_goes_postal(self):
        self.assertEqual(choose(parcel(30, 800)), "postal")

    def test_a_heavy_one_goes_by_courier(self):
        self.assertEqual(choose(parcel(40, 9000)), "courier")

    def test_the_very_large_go_freight(self):
        self.assertEqual(choose(parcel(110, 4000)), "freight")

    def test_the_plan_groups_by_carrier_in_order(self):
        groups = plan([parcel(30, 800, id="A"), parcel(40, 9000, id="B"), parcel(30, 700, id="C")])
        self.assertEqual({name: [p.id for p in items] for name, items in groups.items()}, {"postal": ["A", "C"], "courier": ["B"]})

    def test_vans_by_volume(self):
        self.assertEqual(vans_needed([]), 0)
        self.assertEqual(vans_needed([parcel(30)]), 1)


if __name__ == "__main__":
    unittest.main()
