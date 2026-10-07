"""A JSON document of the expenses and their total."""

import json


def render(expenses):
    document = {
        "expenses": [{"date": e.date, "category": e.category, "note": e.note, "amount_cents": e.cents} for e in expenses],
        "total_cents": sum(e.cents for e in expenses),
    }
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"
