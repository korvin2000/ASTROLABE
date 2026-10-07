import unittest

from parcelhub.domain.errors import ParcelError
from parcelhub.domain.parcel import SizeClass

from support import parcel


class Sizes(unittest.TestCase):
    def test_the_size_classes_by_the_longest_side(self):
        self.assertEqual(parcel(25).size_class, SizeClass.S)
        self.assertEqual(parcel(45).size_class, SizeClass.M)
        self.assertEqual(parcel(95).size_class, SizeClass.L)
        self.assertEqual(parcel(130).size_class, SizeClass.XL)

    def test_a_parcel_is_bulky_when_it_is_class_xl(self):
        self.assertFalse(parcel(99).is_bulky)
        self.assertTrue(parcel(130).is_bulky)

    def test_a_parcel_must_exist(self):
        with self.assertRaises(ParcelError):
            parcel(0)
        with self.assertRaises(ParcelError):
            parcel(30, weight_g=0)


if __name__ == "__main__":
    unittest.main()
