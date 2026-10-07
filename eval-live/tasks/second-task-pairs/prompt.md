The reservations from the last change are invisible to the people who need them. Make them visible:

- `Inventory.reservations(order_id=None)` returns the held reservations ordered by their number (`R-2` before `R-10`):
  all of them, or only those of `order_id`.
- The shop answers `GET /orders/<id>/reservations` with `200` and the order's held reservations as
  `[{"id": "R-1", "sku": "A-1", "qty": 2}, …]` (an empty list once the order is cancelled or shipped), and `404` with
  `{"error": "no_such_order"}` for an unknown order.
- The operator desk gets `holds(inventory)`: one line per reservation the desk holds, `R-3: 2 x A-1`, joined by
  newlines, or `no holds`.
- The morning report ends with one more line, `Desk holds: 3 units in 2 reservations` (`1 unit in 1 reservation`;
  `Desk holds: none` when the desk holds nothing). Only the desk's own reservations count there.

Add tests for these and keep the existing ones passing.
