"""The daily report: one line per order and the total of the day per currency."""

from shop.money import format_amount
from shop.orders import total_cents


def daily_report(orders):
    lines = ["Daily report"]
    totals = {}
    for order in orders:
        amount = total_cents(order)
        currency = order["currency"]
        totals[currency] = totals.get(currency, 0) + amount
        lines.append(f"{order['id']}: {format_amount(amount, currency)}")
    for currency in sorted(totals):
        lines.append(f"Total {currency}: {format_amount(totals[currency], currency)}")
    return "\n".join(lines)
