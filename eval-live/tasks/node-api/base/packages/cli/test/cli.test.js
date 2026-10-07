import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseItem, run } from '../src/main.js';

test('parseItem', () => {
  assert.deepEqual(parseItem('A-1:2:450'), { sku: 'A-1', qty: 2, unitCents: 450 });
  assert.throws(() => parseItem('A-1:two:450'), RangeError);
});

test('prints the total in the region currency', () => {
  assert.deepEqual(run(['--region', 'UK', 'A-1:2:450']), { code: 0, out: 'Total: £10.80\n' });
  assert.deepEqual(run(['A-1:1:1000']), { code: 0, out: 'Total: $10.70\n' });
});

test('errors', () => {
  assert.equal(run(['--region', 'MARS', 'A-1:1:1']).code, 1);
  assert.equal(run([]).code, 2);
});
