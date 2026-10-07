from datetime import timedelta

from .timeutil import UTC, utc_day


def is_due_today(reminder, user, now):
    return utc_day(reminder.due) == utc_day(now)


def is_overdue(reminder, now):
    return reminder.due < now and not reminder.sent


def next_send_time(reminder, user):
    """Quiet hours: 22:00 up to 07:00 hold a reminder until 07:00."""
    if reminder.urgent:
        return reminder.due
    due = reminder.due.astimezone(UTC)
    if due.hour >= 22:
        return (due + timedelta(days=1)).replace(hour=7, minute=0, second=0, microsecond=0)
    if due.hour < 7:
        return due.replace(hour=7, minute=0, second=0, microsecond=0)
    return due
