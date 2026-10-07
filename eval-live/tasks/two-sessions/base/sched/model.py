from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class User:
    id: str
    name: str
    utc_offset_minutes: int = 0  # the user's fixed offset from UTC, east positive


@dataclass(frozen=True)
class Reminder:
    id: str
    user_id: str
    text: str
    due: datetime  # an aware instant
    urgent: bool = False
    sent: bool = False

    def __post_init__(self):
        if self.due.tzinfo is None:
            raise ValueError("a reminder is due at an aware instant, not a naive datetime")
