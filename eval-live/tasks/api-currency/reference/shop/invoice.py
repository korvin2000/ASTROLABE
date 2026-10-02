"""The invoice of one order."""

from shop.money import format_amount
from shop.orders import line_cents, total_cents


def render_invoice(order):
    currency = order["currency"]
    lines = [f"Invoice {order['id']}"]
    for line in order["lines"]:
        lines.append(f"{line['name']} x{line['qty']}: {format_amount(line_cents(line), currency)}")
    lines.append(f"Total: {format_amount(total_cents(order), currency)}")
    return "\n".join(lines)
