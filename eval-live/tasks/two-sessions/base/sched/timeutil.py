from datetime import timedelta, timezone

UTC = timezone.utc


def as_utc(moment):
    if moment.tzinfo is None:
        raise ValueError("naive datetime: every instant in this program is timezone-aware")
    return moment.astimezone(UTC)


def utc_day(moment):
    """The calendar date of ``moment`` in UTC."""
    return as_utc(moment).date()


def to_local(moment, utc_offset_minutes):
    """``moment`` as wall-clock time in a zone ``utc_offset_minutes`` from UTC (fixed offsets: no daylight saving here)."""
    return as_utc(moment).astimezone(timezone(timedelta(minutes=utc_offset_minutes)))
