def hhmm(moment, utc_offset_minutes=0):
    """``HH:MM`` of ``moment``."""
    return moment.strftime("%H:%M")
