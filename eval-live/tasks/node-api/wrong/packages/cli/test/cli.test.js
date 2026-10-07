import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseItem, run } from '../src/main.js';

test('parseItem', () => {
  assert.deepEqual(parseItem('A-1:2:450'), { sku: 'A-1', qty: 2, unitCents: 450 });
  assert.throws(() => parseItem('A-1:two:450'), RangeError);
});

test('prints the breakdown in the region currency', () => {
  assert.deepEqual(run(['--region', 'UK', 'A-1:2:450']), { code: 0, out: 'Subtotal: £9.00\nTax: £1.80\nTotal: £10.80\n' });
  assert.deepEqual(run(['--region', 'EU', '--coupon', 'SAVE10', 'A-1:2:450']), {
    code: 0,
    out: 'Subtotal: €9.00\nDiscount: -€0.90\nTax: €1.62\nTotal: €9.72\n',
  });
});

test('errors', () => {
  assert.equal(run(['--region', 'MARS', 'A-1:1:1']).code, 1);
  assert.deepEqual(run(['--coupon', 'FREE', 'A-1:1:1']), { code: 1, out: 'error: unknown coupon FREE\n' });
  assert.equal(run([]).code, 2);
});
