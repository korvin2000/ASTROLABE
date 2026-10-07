"""Plain text: one line per expense and a total."""

from spend.core.money import format_cents


def render(expenses):
    lines = [f"{e.date}  {e.category:<12}{format_cents(e.cents):>10}  {e.note}".rstrip() for e in expenses]
    lines.append(f"{'total':<24}{format_cents(sum(e.cents for e in expenses)):>10}")
    return "\n".join(lines) + "\n"
