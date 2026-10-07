import unittest

from sched.cli.format import hhmm
from sched.cli.today import render_today
from sched.store import ReminderStore
from sched.web.handlers import digest_view, today_view

from support import UTC_USER, at, reminder


class Listings(unittest.TestCase):
    def setUp(self):
        self.store = ReminderStore()
        self.store.add(reminder("a", at(7, 9, 5), text="call Ann"))
        self.store.add(reminder("b", at(8, 9)))

    def test_hhmm(self):
        self.assertEqual(hhmm(at(7, 9, 5)), "09:05")

    def test_the_today_text(self):
        self.assertEqual(render_today(self.store, UTC_USER, at(7, 12)), "Today, 2026-10-07 (1 reminder)\n- 09:05 call Ann")

    def test_the_web_views(self):
        view = today_view(self.store, UTC_USER, at(7, 12))
        self.assertEqual((view["date"], view["count"], [r["id"] for r in view["reminders"]]), ("2026-10-07", 1, ["a"]))
        self.assertEqual(digest_view(self.store, UTC_USER, at(7, 12), days=2), [{"day": "2026-10-07", "count": 1}, {"day": "2026-10-08", "count": 1}])


if __name__ == "__main__":
    unittest.main()
