from datetime import time, timedelta

from .timeutil import UTC, local_day, to_local

QUIET_FROM = time(22, 0)
QUIET_UNTIL = time(7, 0)


def is_due_today(reminder, user, now):
    return local_day(reminder.due, user.utc_offset_minutes) == local_day(now, user.utc_offset_minutes)


def is_overdue(reminder, now):
    return reminder.due < now and not reminder.sent


def next_send_time(reminder, user):
    """When ``reminder`` goes out: at its due instant, except in the quiet hours of the user's own day (22:00 up to
    07:00), when it waits for the next 07:00 there. An urgent reminder is never held."""
    if reminder.urgent:
        return reminder.due
    local = to_local(reminder.due, user.utc_offset_minutes)
    if local.time() >= QUIET_FROM:
        release = (local + timedelta(days=1)).replace(hour=7, minute=0, second=0, microsecond=0)
    elif local.time() < QUIET_UNTIL:
        release = local.replace(hour=7, minute=0, second=0, microsecond=0)
    else:
        return reminder.due
    return release.astimezone(UTC)
