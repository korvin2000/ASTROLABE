import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatMoney, quote } from '../src/index.js';

const ITEMS = [
  { sku: 'A-1', qty: 2, unitCents: 450 },
  { sku: 'B-7', qty: 1, unitCents: 1299 },
];

test('quote adds the region tax', () => {
  assert.equal(quote(ITEMS, 'US'), 23.53);
  assert.equal(quote(ITEMS, 'EU'), 26.39);
});

test('an unknown region is refused', () => {
  assert.throws(() => quote(ITEMS, 'MARS'), RangeError);
});

test('formatMoney', () => {
  assert.equal(formatMoney(12.5, 'EUR'), '€12.50');
  assert.equal(formatMoney(-3, 'GBP'), '-£3.00');
  assert.equal(formatMoney(7), '$7.00');
  assert.equal(formatMoney(30, 'CHF'), '30.00 CHF');
});
