from dataclasses import replace


class ReminderStore:
    def __init__(self):
        self._items = {}

    def add(self, reminder):
        self._items[reminder.id] = reminder
        return reminder

    def get(self, reminder_id):
        return self._items.get(reminder_id)

    def for_user(self, user_id):
        """The user's reminders, earliest first."""
        return sorted((r for r in self._items.values() if r.user_id == user_id), key=lambda r: (r.due, r.id))

    def mark_sent(self, reminder_id):
        self._items[reminder_id] = replace(self._items[reminder_id], sent=True)
