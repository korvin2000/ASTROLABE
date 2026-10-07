import unittest

from parcelhub.rating import quote

from support import parcel


class Rating(unittest.TestCase):
    def test_a_small_domestic_parcel_has_a_base_price_and_fuel(self):
        result = quote(parcel(30, 400))
        self.assertEqual(result.lines, {"base": 390, "fuel": 27})
        self.assertEqual(result.total_cents, 420)

    def test_a_bulky_parcel_pays_the_bulky_surcharge(self):
        result = quote(parcel(130, 400))
        self.assertEqual(result.lines["bulky"], 2500)

    def test_a_parcel_below_the_line_does_not(self):
        self.assertNotIn("bulky", quote(parcel(95, 400)).lines)
        self.assertNotIn("bulky", quote(parcel(120, 400)).lines)

    def test_a_far_destination_is_remote(self):
        self.assertEqual(quote(parcel(30, 400, "US")).lines["remote"], 400)

    def test_loyalty_takes_two_percent_a_year(self):
        plain, loyal = quote(parcel(30, 400)), quote(parcel(30, 400), loyalty_years=5)
        self.assertEqual(loyal.lines["loyalty"], -(417 * 10 // 100))
        self.assertLess(loyal.total_cents, plain.total_cents)


if __name__ == "__main__":
    unittest.main()
