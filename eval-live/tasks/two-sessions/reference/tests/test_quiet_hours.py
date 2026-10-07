import unittest

from sched.model import User
from sched.notify import due_notifications
from sched.rules import next_send_time
from sched.store import ReminderStore

from support import at, reminder

TOKYO = User("t", "Aiko", 540)
DELHI = User("d", "Ravi", 330)


class QuietHours(unittest.TestCase):
    def test_a_reminder_in_the_quiet_hours_waits_for_seven_in_the_morning(self):
        self.assertEqual(next_send_time(reminder("a", at(7, 14, 30), "t"), TOKYO), at(7, 22, 0))  # 23:30 -> 07:00 on the 8th
        self.assertEqual(next_send_time(reminder("a", at(7, 20), "t"), TOKYO), at(7, 22, 0))  # 05:00 -> 07:00 the same day
        self.assertEqual(next_send_time(reminder("a", at(7, 17), "d"), DELHI), at(8, 1, 30))

    def test_the_edges(self):
        self.assertEqual(next_send_time(reminder("a", at(7, 13), "t"), TOKYO), at(7, 22))  # 22:00 is quiet
        self.assertEqual(next_send_time(reminder("a", at(7, 12, 59), "t"), TOKYO), at(7, 12, 59))
        self.assertEqual(next_send_time(reminder("a", at(7, 22), "t"), TOKYO), at(7, 22))  # 07:00 is not

    def test_urgent_is_never_held(self):
        self.assertEqual(next_send_time(reminder("a", at(7, 14, 30), "t", urgent=True), TOKYO), at(7, 14, 30))

    def test_notifications_wait_for_the_send_time(self):
        store = ReminderStore()
        store.add(reminder("a", at(7, 14, 30), "t"))
        self.assertEqual(due_notifications(store, TOKYO, at(7, 15)), [])
        self.assertEqual([r.id for r in due_notifications(store, TOKYO, at(7, 22))], ["a"])


if __name__ == "__main__":
    unittest.main()
