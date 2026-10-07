"""A ledger file: one expense per line, `date|category|amount|note`; blank lines and `#` comments are skipped."""

from spend.core.model import Expense
from spend.core.money import parse_amount


def parse(text):
    expenses = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|", 3)
        if len(parts) < 3:
            raise ValueError(f"line {number}: expected date|category|amount|note")
        date, category, amount = (p.strip() for p in parts[:3])
        note = parts[3].strip() if len(parts) == 4 else ""
        expenses.append(Expense(date, category, note, parse_amount(amount)))
    return expenses


def load(path):
    with open(path, encoding="utf-8") as f:
        return parse(f.read())
