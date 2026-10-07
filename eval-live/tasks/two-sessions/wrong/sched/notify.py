from .rules import next_send_time


def due_notifications(store, user, now):
    """The reminders to send now: past their send time, and not sent yet."""
    return [r for r in store.for_user(user.id) if next_send_time(r, user) <= now and not r.sent]


def send_due(store, user, now, send):
    """Calls ``send(user, reminder)`` for each due reminder and marks it sent; returns how many went out."""
    due = due_notifications(store, user, now)
    for reminder in due:
        send(user, reminder)
        store.mark_sent(reminder.id)
    return len(due)
