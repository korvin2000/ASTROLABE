"""The daily report: one line per order and the total of the day."""

from shop.money import format_amount
from shop.orders import total_cents


def daily_report(orders):
    lines = ["Daily report"]
    total = 0
    for order in orders:
        amount = total_cents(order)
        total += amount
        lines.append(f"{order['id']}: {format_amount(amount)}")
    lines.append(f"Total: {format_amount(total)}")
    return "\n".join(lines)
