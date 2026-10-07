import { region as regionOf } from './rates.js';

const COUPONS = Object.freeze({
  SAVE10: (subtotal) => Math.round(subtotal / 10),
  FLAT5: (subtotal) => Math.min(500, subtotal),
});

/**
 * The price of `items` (`{ sku, qty, unitCents }`) in `options.region`, with `options.coupon` if given: every amount in
 * integer cents of the region's currency.
 */
export function quote(items, options) {
  if (options === null || typeof options !== 'object') throw new TypeError('quote needs { region, coupon }');
  const { region, coupon } = options;
  if (region === undefined) throw new TypeError('quote needs a region');
  const { taxBasisPoints, currency } = regionOf(region);
  let subtotal = 0;
  for (const item of items) {
    if (!Number.isInteger(item.qty) || item.qty <= 0) throw new RangeError(`bad quantity for ${item.sku}`);
    if (!Number.isInteger(item.unitCents) || item.unitCents < 0) throw new RangeError(`bad price for ${item.sku}`);
    subtotal += item.qty * item.unitCents;
  }
  let discount = 0;
  if (coupon !== undefined && coupon !== null) {
    const rule = Object.hasOwn(COUPONS, coupon) ? COUPONS[coupon] : null;
    if (!rule) throw new RangeError(`unknown coupon ${coupon}`);
    discount = rule(subtotal);
  }
  const tax = Math.round(((subtotal - discount) * taxBasisPoints) / 10000);
  return { subtotal, discount, tax, total: subtotal - discount + tax, currency };
}
