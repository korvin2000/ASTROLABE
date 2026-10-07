import { region as regionOf } from './rates.js';

/**
 * The price of `items` (`{ sku, qty, unitCents }`) in `region`, tax included, in dollars (or euros, pounds: the
 * region's currency).
 */
export function quote(items, region) {
  const { taxBasisPoints } = regionOf(region);
  let subtotal = 0;
  for (const item of items) {
    if (!Number.isInteger(item.qty) || item.qty <= 0) throw new RangeError(`bad quantity for ${item.sku}`);
    if (!Number.isInteger(item.unitCents) || item.unitCents < 0) throw new RangeError(`bad price for ${item.sku}`);
    subtotal += item.qty * item.unitCents;
  }
  const tax = Math.round((subtotal * taxBasisPoints) / 10000);
  return (subtotal + tax) / 100;
}
