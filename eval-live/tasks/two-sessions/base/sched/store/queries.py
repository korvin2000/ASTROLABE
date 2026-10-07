from ..timeutil import utc_day


def due_today(store, user, now):
    """The user's reminders that fall on today's date."""
    today = utc_day(now)
    return [r for r in store.for_user(user.id) if utc_day(r.due) == today]


def due_between(store, user, start, end):
    """Reminders due from ``start`` (inclusive) to ``end`` (exclusive), two instants."""
    return [r for r in store.for_user(user.id) if start <= r.due < end]


def overdue(store, user, now):
    return [r for r in store.for_user(user.id) if r.due < now and not r.sent]
