from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta

from .timeutil import local_day


@dataclass(frozen=True)
class DigestDay:
    day: date
    reminders: list


def group_by_day(reminders, user):
    """The reminders by the calendar day they fall on, earliest first within a day."""
    days = defaultdict(list)
    for reminder in reminders:
        days[local_day(reminder.due, user.utc_offset_minutes)].append(reminder)
    return dict(days)


def build_digest(store, user, now, days=3):
    """The next ``days`` days counting from today, each with its reminders; days without any are kept, empty."""
    grouped = group_by_day(store.for_user(user.id), user)
    first = local_day(now, user.utc_offset_minutes)
    wanted = [first + timedelta(days=n) for n in range(days)]
    return [DigestDay(day, grouped.get(day, [])) for day in wanted]


def render_digest(digest):
    lines = []
    for entry in digest:
        lines.append("%s: %d" % (entry.day.isoformat(), len(entry.reminders)))
        lines += ["  - %s" % r.text for r in entry.reminders]
    return "\n".join(lines)
