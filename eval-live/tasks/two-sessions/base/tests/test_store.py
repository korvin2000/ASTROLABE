import unittest

from sched.store import ReminderStore
from sched.store.queries import due_between, due_today, overdue

from support import UTC_USER, at, reminder


class Store(unittest.TestCase):
    def setUp(self):
        self.store = ReminderStore()
        for r in (reminder("a", at(7, 9)), reminder("b", at(7, 18)), reminder("c", at(8, 9)), reminder("d", at(7, 12), user="other")):
            self.store.add(r)

    def test_a_user_sees_their_own_reminders_earliest_first(self):
        self.assertEqual([r.id for r in self.store.for_user("u0")], ["a", "b", "c"])

    def test_due_today_is_the_calendar_day(self):
        self.assertEqual([r.id for r in due_today(self.store, UTC_USER, at(7, 23))], ["a", "b"])

    def test_due_between_is_half_open(self):
        self.assertEqual([r.id for r in due_between(self.store, UTC_USER, at(7, 9), at(8, 9))], ["a", "b"])

    def test_overdue_leaves_out_what_was_sent(self):
        self.store.mark_sent("a")
        self.assertEqual([r.id for r in overdue(self.store, UTC_USER, at(8, 0))], ["b"])


if __name__ == "__main__":
    unittest.main()
