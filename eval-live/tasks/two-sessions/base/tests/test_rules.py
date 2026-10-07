import unittest

from sched.notify import due_notifications, send_due
from sched.rules import is_due_today, is_overdue
from sched.store import ReminderStore

from support import UTC_USER, at, reminder


class Rules(unittest.TestCase):
    def test_due_today_and_overdue(self):
        r = reminder("a", at(7, 9))
        self.assertTrue(is_due_today(r, UTC_USER, at(7, 23)))
        self.assertFalse(is_due_today(r, UTC_USER, at(8, 0)))
        self.assertTrue(is_overdue(r, at(7, 10)))
        self.assertFalse(is_overdue(r, at(7, 8)))

    def test_notifications_are_the_due_unsent_ones(self):
        store = ReminderStore()
        for r in (reminder("a", at(7, 9)), reminder("b", at(7, 18))):
            store.add(r)
        self.assertEqual([r.id for r in due_notifications(store, UTC_USER, at(7, 12))], ["a"])
        sent = []
        self.assertEqual(send_due(store, UTC_USER, at(7, 12), lambda user, r: sent.append(r.id)), 1)
        self.assertEqual((sent, due_notifications(store, UTC_USER, at(7, 12))), (["a"], []))


if __name__ == "__main__":
    unittest.main()
