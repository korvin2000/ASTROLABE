"""Stage 2: typed invoices from raw rows."""

from dataclasses import dataclass
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal

CENT = Decimal("0.01")


@dataclass(frozen=True)
class Invoice:
    id: str
    customer: str
    issued_at: datetime
    amount: Decimal


def normalize(rows):
    """Invoices from raw rows: void ones dropped, text parsed, oldest first.

    `issued_at` is ISO 8601 local time, with or without a time part; a bare date means midnight.
    Amounts are exact decimals rounded half up to the cent.
    """
    invoices = []
    for row in rows:
        if row["status"].strip().lower() == "void":
            continue
        invoices.append(
            Invoice(
                id=row["id"].strip(),
                customer=row["customer"].strip(),
                issued_at=datetime.fromisoformat(row["issued_at"].strip()),
                amount=Decimal(row["amount"].replace(",", "")).quantize(CENT, rounding=ROUND_HALF_UP),
            )
        )
    return sorted(invoices, key=lambda invoice: invoice.issued_at)
