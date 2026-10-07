from ..digest import build_digest
from ..store.queries import due_today
from ..timeutil import utc_day
from .serialize import reminder_json


def today_view(store, user, now):
    """The JSON of the today list: its date, how many reminders, the reminders."""
    items = due_today(store, user, now)
    return {"date": utc_day(now).isoformat(), "count": len(items), "reminders": [reminder_json(r) for r in items]}


def digest_view(store, user, now, days=3):
    return [{"day": entry.day.isoformat(), "count": len(entry.reminders)} for entry in build_digest(store, user, now, days)]
