"""The one-line receipt of a paid order."""

from shop.money import format_amount
from shop.orders import total_cents


def render_receipt(order):
    return f"Receipt {order['id']}: {format_amount(total_cents(order), order['currency'])}"
