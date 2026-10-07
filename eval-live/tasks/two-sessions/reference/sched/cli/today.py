from ..store.queries import due_today
from ..timeutil import local_day
from .format import hhmm


def render_today(store, user, now):
    items = due_today(store, user, now)
    lines = ["Today, %s (%d reminder%s)" % (local_day(now, user.utc_offset_minutes).isoformat(), len(items), "" if len(items) == 1 else "s")]
    lines += ["- %s %s" % (hhmm(r.due, user.utc_offset_minutes), r.text) for r in items]
    return "\n".join(lines)
