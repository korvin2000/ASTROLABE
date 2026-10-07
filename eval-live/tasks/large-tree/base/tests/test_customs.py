import unittest

from parcelhub.customs import declaration_lines, needs_extra_form
from parcelhub.customs.duty import duty_cents

from support import parcel


class Customs(unittest.TestCase):
    def test_a_long_parcel_needs_the_extra_form(self):
        self.assertTrue(needs_extra_form(parcel(110)))
        self.assertFalse(needs_extra_form(parcel(99)))
        self.assertIn("FORM C-17", declaration_lines(parcel(110, country="US"), "books", 5000))

    def test_duty_starts_at_150(self):
        self.assertEqual(duty_cents(14999, "US"), 0)
        self.assertEqual(duty_cents(20000, "US"), 2400)
        self.assertEqual(duty_cents(20000, "DE"), 0)


if __name__ == "__main__":
    unittest.main()
