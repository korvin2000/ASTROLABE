import unittest

from sched.cli.format import hhmm
from sched.cli.today import render_today
from sched.digest import build_digest, group_by_day
from sched.model import User
from sched.rules import is_due_today
from sched.store import ReminderStore
from sched.store.queries import due_today
from sched.timeutil import local_day
from sched.web.handlers import today_view

from support import at, reminder

TOKYO = User("t", "Aiko", 540)


class LocalDays(unittest.TestCase):
    def test_a_day_is_the_users_day(self):
        self.assertEqual(local_day(at(7, 20), 540).isoformat(), "2026-10-08")
        self.assertEqual(local_day(at(7, 5), -480).isoformat(), "2026-10-06")
        self.assertEqual(local_day(at(7, 18, 30), 330).isoformat(), "2026-10-08")

    def test_grouping_and_the_digest_use_it(self):
        rs = [reminder("a", at(7, 20), "t"), reminder("b", at(7, 7), "t")]
        grouped = group_by_day(rs, TOKYO)
        self.assertEqual({d.isoformat(): [r.id for r in v] for d, v in grouped.items()}, {"2026-10-08": ["a"], "2026-10-07": ["b"]})
        store = ReminderStore()
        for r in rs:
            store.add(r)
        digest = build_digest(store, TOKYO, at(7, 16), days=2)
        self.assertEqual([(d.day.isoformat(), len(d.reminders)) for d in digest], [("2026-10-08", 1), ("2026-10-09", 0)])

    def test_the_today_list_rule_and_views(self):
        store = ReminderStore()
        early, late = reminder("a", at(7, 7), "t"), reminder("b", at(7, 20), "t")
        store.add(early)
        store.add(late)
        now = at(7, 16)  # 01:00 on the 8th in Tokyo
        self.assertEqual([r.id for r in due_today(store, TOKYO, now)], ["b"])
        self.assertTrue(is_due_today(late, TOKYO, now))
        self.assertFalse(is_due_today(early, TOKYO, now))
        self.assertEqual(today_view(store, TOKYO, now)["date"], "2026-10-08")
        self.assertEqual(render_today(store, TOKYO, now), "Today, 2026-10-08 (1 reminder)\n- 05:00 task b")

    def test_times_are_shown_on_the_users_clock(self):
        self.assertEqual(hhmm(at(7, 18, 30), 330), "00:00")
        self.assertEqual(hhmm(at(7, 5), -480), "21:00")


if __name__ == "__main__":
    unittest.main()
