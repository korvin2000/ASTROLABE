"""Stage 4: the text of the report."""


def format_amount(value):
    return f"{value:,.2f}"


def format_report(totals, invoice_count, overall_amount):
    lines = ["Monthly totals"]
    for total in totals:
        lines.append(f"{total.year}-{total.month:02d}  n={total.count}  {format_amount(total.amount)}")
    lines.append(f"Total  n={invoice_count}  {format_amount(overall_amount)}")
    return "\n".join(lines)
