`quote` in `packages/pricing` answers a bare number in dollars (or euros, pounds), takes the region as a second
positional argument and knows no coupons. Change the pricing API:

- `quote(items, { region, coupon })`: the options object and its `region` are required (a call without them throws a
  `TypeError`); `coupon` is optional. It returns `{ subtotal, discount, tax, total, currency }`: every amount in integer
  cents, `currency` the region's currency code.
- Coupons: `SAVE10` takes 10 % off the subtotal, rounded to the nearest cent (half up); `FLAT5` takes 500 cents off,
  never more than the subtotal; any other code throws a `RangeError` with the message `unknown coupon <CODE>`. Without a
  coupon the discount is 0.
- The tax is the region's rate on `subtotal - discount`, rounded to the nearest cent (half up);
  `total = subtotal - discount + tax`.
- `formatMoney(cents, currency)` takes cents, and `currency` is required.

Update both callers in the monorepo:

- The API (`packages/api`): `POST /quote` also accepts an optional `coupon` and answers
  `{ subtotal, discount, tax, total, currency, display }` (amounts in cents, `display` = `formatMoney(total, currency)`);
  an unknown region or coupon answers 400 with `{ "error": <the message> }`.
- The command line (`packages/cli`): a new `--coupon CODE` option, and instead of the single `Total:` line it prints

      Subtotal: €9.00
      Discount: -€0.90
      Tax: €1.62
      Total: €9.72

  (the `Discount` line only when the discount is not 0).

Node built-ins only — no `npm install`, no new dependencies. Update the tests; `node --test` from the root stays green.
