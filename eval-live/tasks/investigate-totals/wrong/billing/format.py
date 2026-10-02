"""Stage 4: the text of the report."""

from decimal import ROUND_HALF_UP, Decimal

CENT = Decimal("0.01")


def format_amount(value):
    return f"{Decimal(str(value)).quantize(CENT, rounding=ROUND_HALF_UP):,.2f}"


def format_report(totals, invoice_count, overall_amount):
    lines = ["Monthly totals"]
    for total in totals:
        lines.append(f"{total.year}-{total.month:02d}  n={total.count}  {format_amount(total.amount)}")
    lines.append(f"Total  n={invoice_count}  {format_amount(overall_amount)}")
    return "\n".join(lines)
