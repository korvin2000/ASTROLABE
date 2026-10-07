`Inventory.reserve` in `depot/stock` answers a bare `True`/`False`, and `release`/`ship` take a SKU and a quantity, so
nothing ties reserved units to the order (or the desk) they were reserved for. Change the stock API:

- `reserve(sku, qty, *, order_id)` — `order_id` keyword-only and required — returns a `Reservation`: a new frozen
  dataclass exported from `depot.stock` with the fields `id`, `sku`, `qty` and `order_id`; ids are `R-1`, `R-2`, … per
  inventory, in the order reservations are made. When fewer than `qty` units are available it reserves nothing and
  raises `OutOfStock`, also exported from `depot.stock`, with the attributes `sku`, `requested` and `available`.
- `release(reservation)` and `ship(reservation)` take a `Reservation` instead of `(sku, qty)`; a reservation that is no
  longer held (already released or shipped) raises `ValueError`.
- `held(reservation_id)` returns the reservation of that id while it is held, else `None`.

Update every caller in the other packages:

- An order keeps the reservations made for it at checkout in a new `reservations` list (its `order_id` is the order's
  id). Checkout stays all-or-nothing and still returns `True`/`False`, a short order rejected with the note
  `short of <sku>` and nothing left reserved; cancelling releases exactly the order's reservations, fulfilment ships
  exactly them.
- The shop's `409` answer for a short order also gives `"available"`: the units of that SKU available.
- The operator desk reserves under the order id `desk`: `hold(inventory, "A-1", 2)` answers `held R-1: 2 x A-1` (or,
  as before, `not enough A-1: 0 available`), and `unhold(inventory, "R-1")` takes the id `hold` printed and answers
  `released R-1: 2 x A-1` (`no reservation R-9` for an id that is not held).

Update the tests to the new API and keep them passing.
