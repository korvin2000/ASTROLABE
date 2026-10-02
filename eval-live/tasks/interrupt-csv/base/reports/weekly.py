"""The weekly sales report: the sales of one week added up per day and category."""

import datetime


class Entry:
    """The sales of one category on one day, in cents."""

    def __init__(self, day, category, amount_cents):
        self.day = day
        self.category = category
        self.amount_cents = amount_cents

    def __eq__(self, other):
        return isinstance(other, Entry) and (self.day, self.category, self.amount_cents) == (other.day, other.category, other.amount_cents)

    def __repr__(self):
        return f"Entry({self.day!r}, {self.category!r}, {self.amount_cents})"


class WeeklyReport:
    """Seven days from `week_start` (a Monday); `entries()` are ordered by day, then category."""

    def __init__(self, week_start, entries):
        if week_start.weekday() != 0:
            raise ValueError(f"a week starts on a Monday, not on {week_start:%A}")
        self.week_start = week_start
        self._entries = sorted(entries, key=lambda e: (e.day, e.category))

    @classmethod
    def from_sales(cls, week_start, sales):
        """`sales` are `(day, category, amount_cents)` tuples; those outside the week are left out."""
        week_end = week_start + datetime.timedelta(days=7)
        totals = {}
        for day, category, cents in sales:
            if week_start <= day < week_end:
                totals[(day, category)] = totals.get((day, category), 0) + cents
        return cls(week_start, [Entry(day, category, cents) for (day, category), cents in totals.items()])

    def entries(self):
        return list(self._entries)

    def total_cents(self):
        return sum(e.amount_cents for e in self._entries)

    def display_rows(self):
        """The rows the dashboard shows: US dates (`03/02/2026`) and amounts with two decimals."""
        return [[e.day.strftime("%m/%d/%Y"), e.category, format_cents(e.amount_cents)] for e in self._entries]


def format_cents(cents):
    """1250 -> "12.50"."""
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    return f"{sign}{cents // 100}.{cents % 100:02d}"
