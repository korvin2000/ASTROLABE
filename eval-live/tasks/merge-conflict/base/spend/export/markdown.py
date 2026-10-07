"""A Markdown table with a total row."""

from spend.core.money import format_cents


def _cell(value):
    return str(value).replace("|", "\\|")


def render(expenses):
    lines = ["| Date | Category | Note | Amount |", "|---|---|---|---:|"]
    for e in expenses:
        lines.append(f"| {e.date} | {_cell(e.category)} | {_cell(e.note)} | {format_cents(e.cents)} |")
    lines.append(f"| | | **Total** | {format_cents(sum(e.cents for e in expenses))} |")
    return "\n".join(lines) + "\n"
