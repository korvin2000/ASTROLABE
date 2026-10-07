import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatMoney, quote } from '../src/index.js';

const ITEMS = [
  { sku: 'A-1', qty: 2, unitCents: 450 },
  { sku: 'B-7', qty: 1, unitCents: 1299 },
];

test('quote adds the region tax, in cents', () => {
  assert.deepEqual(quote(ITEMS, { region: 'US' }), { subtotal: 2199, discount: 0, tax: 154, total: 2353, currency: 'USD' });
  assert.equal(quote(ITEMS, { region: 'EU' }).total, 2639);
});

test('coupons come off before the tax', () => {
  assert.deepEqual(quote(ITEMS, { region: 'EU', coupon: 'SAVE10' }), { subtotal: 2199, discount: 220, tax: 396, total: 2375, currency: 'EUR' });
  assert.equal(quote([{ sku: 'C', qty: 1, unitCents: 300 }], { region: 'US', coupon: 'FLAT5' }).total, 0);
  assert.throws(() => quote(ITEMS, { region: 'US', coupon: 'FREE' }), { name: 'RangeError', message: 'unknown coupon FREE' });
});

test('the region is required and must be known', () => {
  assert.throws(() => quote(ITEMS), TypeError);
  assert.throws(() => quote(ITEMS, {}), TypeError);
  assert.throws(() => quote(ITEMS, { region: 'MARS' }), RangeError);
});

test('formatMoney takes cents', () => {
  assert.equal(formatMoney(1250, 'EUR'), '€12.50');
  assert.equal(formatMoney(-300, 'GBP'), '-£3.00');
  assert.equal(formatMoney(7, 'USD'), '$0.07');
  assert.equal(formatMoney(3000, 'CHF'), '30.00 CHF');
});
