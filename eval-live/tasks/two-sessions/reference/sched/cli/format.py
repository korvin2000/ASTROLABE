from ..timeutil import to_local


def hhmm(moment, utc_offset_minutes=0):
    """``HH:MM`` of ``moment`` on the wall clock of a zone ``utc_offset_minutes`` from UTC."""
    return to_local(moment, utc_offset_minutes).strftime("%H:%M")
