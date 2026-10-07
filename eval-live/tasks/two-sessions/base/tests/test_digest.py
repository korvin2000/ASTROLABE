import unittest

from sched.digest import build_digest, group_by_day, render_digest
from sched.store import ReminderStore

from support import UTC_USER, at, reminder


class Digest(unittest.TestCase):
    def setUp(self):
        self.store = ReminderStore()
        for r in (reminder("a", at(7, 9)), reminder("b", at(7, 18)), reminder("c", at(9, 9))):
            self.store.add(r)

    def test_grouping_by_day(self):
        grouped = group_by_day(self.store.for_user("u0"), UTC_USER)
        self.assertEqual({day.isoformat(): [r.id for r in rs] for day, rs in grouped.items()}, {"2026-10-07": ["a", "b"], "2026-10-09": ["c"]})

    def test_the_digest_keeps_empty_days(self):
        digest = build_digest(self.store, UTC_USER, at(7, 6))
        self.assertEqual([(d.day.isoformat(), len(d.reminders)) for d in digest], [("2026-10-07", 2), ("2026-10-08", 0), ("2026-10-09", 1)])

    def test_the_text(self):
        text = render_digest(build_digest(self.store, UTC_USER, at(7, 6), days=2))
        self.assertEqual(text, "2026-10-07: 2\n  - task a\n  - task b\n2026-10-08: 0")


if __name__ == "__main__":
    unittest.main()
