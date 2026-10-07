from .timeutil import utc_day


def is_due_today(reminder, user, now):
    return utc_day(reminder.due) == utc_day(now)


def is_overdue(reminder, now):
    return reminder.due < now and not reminder.sent
