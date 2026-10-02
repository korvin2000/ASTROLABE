import unittest

from units.convert import celsius_to_fahrenheit, convert_length, fahrenheit_to_celsius


class ConvertTest(unittest.TestCase):
    def test_temperatures(self):
        self.assertEqual(celsius_to_fahrenheit(100), 212)
        self.assertAlmostEqual(fahrenheit_to_celsius(-40), -40)

    def test_lengths(self):
        self.assertAlmostEqual(convert_length(1, "mi", "km"), 1.609344)
        self.assertAlmostEqual(convert_length(3, "ft", "m"), 0.9144)

    def test_unknown_unit(self):
        with self.assertRaises(ValueError):
            convert_length(1, "yd", "m")


if __name__ == "__main__":
    unittest.main()
