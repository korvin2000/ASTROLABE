"""Stage 2: typed invoices from raw rows."""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Invoice:
    id: str
    customer: str
    issued_at: datetime
    amount: float


def normalize(rows):
    """Invoices from raw rows: void ones dropped, text parsed, oldest first.

    `issued_at` is ISO 8601 local time, with or without a time part; a bare date means midnight.
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
                amount=round(float(row["amount"].replace(",", "")), 2),
            )
        )
    return sorted(invoices, key=lambda invoice: invoice.issued_at)
