import unittest
from datetime import datetime

from sched.model import Reminder
from sched.timeutil import as_utc, to_local, utc_day

from support import at


class Model(unittest.TestCase):
    def test_a_reminder_needs_an_aware_instant(self):
        with self.assertRaises(ValueError):
            Reminder("r", "u", "x", datetime(2026, 10, 7, 9, 0))

    def test_the_helpers(self):
        moment = at(7, 23, 30)
        self.assertEqual(utc_day(moment).isoformat(), "2026-10-07")
        self.assertEqual(to_local(moment, 540).isoformat(), "2026-10-08T08:30:00+09:00")
        self.assertEqual(to_local(moment, -480).hour, 15)
        with self.assertRaises(ValueError):
            as_utc(datetime(2026, 10, 7))


if __name__ == "__main__":
    unittest.main()
