from ..store.queries import due_today
from ..timeutil import utc_day
from .format import hhmm


def render_today(store, user, now):
    items = due_today(store, user, now)
    lines = ["Today, %s (%d reminder%s)" % (utc_day(now).isoformat(), len(items), "" if len(items) == 1 else "s")]
    lines += ["- %s %s" % (hhmm(r.due), r.text) for r in items]
    return "\n".join(lines)
