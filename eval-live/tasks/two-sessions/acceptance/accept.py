"""Hidden acceptance of two-sessions: run from the root of a copy of the finished workspace.

Part one: every place that groups, lists or counts reminders by day, or shows a time of day, uses the user's own
day. Part two: quiet hours. The expectations are computed here by plain offset arithmetic, not by the program's helpers.
"""

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from sched.cli.today import render_today  # noqa: E402
from sched.digest import build_digest, group_by_day  # noqa: E402
from sched.model import Reminder, User  # noqa: E402
from sched.notify import due_notifications, send_due  # noqa: E402
from sched.rules import is_due_today  # noqa: E402
from sched.store import ReminderStore  # noqa: E402
from sched.store.queries import due_between, due_today, overdue  # noqa: E402
from sched.web.handlers import digest_view, today_view  # noqa: E402

try:
    from sched.rules import next_send_time
except ImportError:  # part two is judged on its own: a missing function fails its tests, not the import
    next_send_time = None

UTC = timezone.utc
USERS = [User("t", "Aiko", 540), User("l", "Sam", -480), User("d", "Ravi", 330), User("u", "Lee", 0), User("o", "Kim", -210)]


def at(day, hour=0, minute=0, month=10):
    return datetime(2026, month, day, hour, minute, tzinfo=UTC)


NOW = at(7, 16, 0)


def local(moment, user):
    """The user's wall clock as a naive datetime, by plain arithmetic."""
    return (moment.astimezone(UTC) + timedelta(minutes=user.utc_offset_minutes)).replace(tzinfo=None)


def day_of(moment, user):
    return local(moment, user).date()


def reminders_of(user):
    due = [at(6, 23, 30), at(7, 7, 30), at(7, 20, 15), at(8, 3, 0), at(9, 12, 0)]
    return [Reminder("%s%d" % (user.id, n), user.id, "task %d" % n, moment) for n, moment in enumerate(due)]


def store_with(user):
    store = ReminderStore()
    for r in reminders_of(user):
        store.add(r)
    return store


class PartOneTheUsersDay(unittest.TestCase):
    def test_grouping_by_day(self):
        for user in USERS:
            grouped = group_by_day(reminders_of(user), user)
            expected = {}
            for r in reminders_of(user):
                expected.setdefault(day_of(r.due, user), []).append(r.id)
            self.assertEqual({day: [r.id for r in rs] for day, rs in grouped.items()}, expected, user.name)

    def test_the_digest_counts_from_the_users_today(self):
        for user in USERS:
            digest = build_digest(store_with(user), user, NOW, days=4)
            first = day_of(NOW, user)
            self.assertEqual([entry.day for entry in digest], [first + timedelta(days=n) for n in range(4)], user.name)
            counts = [sum(1 for r in reminders_of(user) if day_of(r.due, user) == entry.day) for entry in digest]
            self.assertEqual([len(entry.reminders) for entry in digest], counts, user.name)
            self.assertEqual([item["day"] for item in digest_view(store_with(user), user, NOW, 4)], [e.day.isoformat() for e in digest], user.name)

    def test_the_today_query_and_the_rule(self):
        for user in USERS:
            wanted = [r.id for r in reminders_of(user) if day_of(r.due, user) == day_of(NOW, user)]
            self.assertEqual([r.id for r in due_today(store_with(user), user, NOW)], wanted, user.name)
            self.assertEqual([r.id for r in reminders_of(user) if is_due_today(r, user, NOW)], wanted, user.name)

    def test_the_web_view(self):
        for user in USERS:
            wanted = [r.id for r in reminders_of(user) if day_of(r.due, user) == day_of(NOW, user)]
            view = today_view(store_with(user), user, NOW)
            self.assertEqual((view["date"], view["count"], [r["id"] for r in view["reminders"]]), (day_of(NOW, user).isoformat(), len(wanted), wanted), user.name)

    def test_the_text_listing_shows_the_users_day_and_clock(self):
        for user in USERS:
            items = [r for r in reminders_of(user) if day_of(r.due, user) == day_of(NOW, user)]
            lines = ["Today, %s (%d reminder%s)" % (day_of(NOW, user).isoformat(), len(items), "" if len(items) == 1 else "s")]
            lines += ["- %s %s" % (local(r.due, user).strftime("%H:%M"), r.text) for r in items]
            self.assertEqual(render_today(store_with(user), user, NOW), "\n".join(lines), user.name)

    def test_instants_are_untouched(self):
        user, store = USERS[0], store_with(USERS[0])
        self.assertEqual([r.id for r in due_between(store, user, at(7, 7, 30), at(8, 3, 0))], ["t1", "t2"])
        self.assertEqual([r.id for r in overdue(store, user, NOW)], ["t0", "t1"])


def expected_send(reminder, user):
    if reminder.urgent:
        return reminder.due
    wall = local(reminder.due, user)
    minutes = wall.hour * 60 + wall.minute
    if minutes >= 22 * 60:
        release = datetime.combine(wall.date() + timedelta(days=1), datetime.min.time()).replace(hour=7)
    elif minutes < 7 * 60:
        release = datetime.combine(wall.date(), datetime.min.time()).replace(hour=7)
    else:
        return reminder.due
    return (release - timedelta(minutes=user.utc_offset_minutes)).replace(tzinfo=UTC)


class PartTwoQuietHours(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(next_send_time, "sched.rules.next_send_time does not exist")

    def samples(self):
        start = at(6, 0, 0)
        return [start + timedelta(minutes=30 * n) for n in range(96)] + [at(7, 12, 59), at(7, 13, 0), at(7, 21, 59), at(7, 22, 0), at(8, 6, 59), at(8, 7, 0)]

    def test_the_send_time_over_two_days_for_every_zone(self):
        for user in USERS:
            for moment in self.samples():
                r = Reminder("x", user.id, "x", moment)
                got = next_send_time(r, user)
                self.assertIsNotNone(got.tzinfo, "the send time is an aware instant")
                self.assertEqual(got, expected_send(r, user), "%s due %s" % (user.name, moment.isoformat()))
                self.assertGreaterEqual(got, moment)

    def test_an_urgent_reminder_is_never_held(self):
        for user in USERS:
            for moment in self.samples():
                self.assertEqual(next_send_time(Reminder("x", user.id, "x", moment, urgent=True), user), moment)

    def test_the_edges_for_tokyo(self):
        tokyo = USERS[0]
        self.assertEqual(next_send_time(Reminder("x", "t", "x", at(7, 13, 0)), tokyo), at(7, 22, 0))  # 22:00 is quiet
        self.assertEqual(next_send_time(Reminder("x", "t", "x", at(7, 12, 59)), tokyo), at(7, 12, 59))  # 21:59 is not
        self.assertEqual(next_send_time(Reminder("x", "t", "x", at(7, 22, 0)), tokyo), at(7, 22, 0))  # 07:00 is not
        self.assertEqual(next_send_time(Reminder("x", "t", "x", at(7, 21, 59)), tokyo), at(7, 22, 0))  # 06:59 is quiet

    def test_notifications_wait_for_the_send_time(self):
        for user in USERS:
            store = store_with(user)
            store.add(Reminder("%surgent" % user.id, user.id, "now", at(7, 14, 30), urgent=True))
            for now in (at(7, 8, 0), at(7, 16, 0), at(7, 23, 0), at(8, 6, 0), at(9, 0, 0)):
                wanted = [r.id for r in store.for_user(user.id) if expected_send(r, user) <= now and not r.sent]
                self.assertEqual([r.id for r in due_notifications(store, user, now)], wanted, "%s at %s" % (user.name, now.isoformat()))

    def test_sending_marks_what_went_out(self):
        user = USERS[0]
        store = store_with(user)
        sent = []
        now = at(8, 6, 0)
        count = send_due(store, user, now, lambda u, r: sent.append(r.id))
        self.assertEqual(sent, [r.id for r in reminders_of(user) if expected_send(r, user) <= now])
        self.assertEqual(count, len(sent))
        self.assertEqual(due_notifications(store, user, now), [])
        self.assertTrue(all(store.get(i).sent for i in sent))


if __name__ == "__main__":
    unittest.main(verbosity=2)
