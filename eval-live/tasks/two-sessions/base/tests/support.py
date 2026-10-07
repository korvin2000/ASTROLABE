from datetime import datetime, timezone

from sched.model import Reminder, User

UTC_USER = User("u0", "Lee", 0)


def at(day, hour=0, minute=0, month=10):
    return datetime(2026, month, day, hour, minute, tzinfo=timezone.utc)


def reminder(rid, due, user="u0", text=None, urgent=False):
    return Reminder(rid, user, text or "task " + rid, due, urgent)
