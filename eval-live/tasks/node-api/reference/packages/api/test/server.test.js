import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createQuoteServer } from '../src/index.js';

async function withServer(run) {
  const server = createQuoteServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  try {
    await run(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function post(base, body) {
  const response = await fetch(`${base}/quote`, { method: 'POST', body: JSON.stringify(body) });
  return { status: response.status, body: await response.json() };
}

test('POST /quote answers the breakdown in cents and the display', async () => {
  await withServer(async (base) => {
    const answer = await post(base, { region: 'EU', coupon: 'SAVE10', items: [{ sku: 'A-1', qty: 2, unitCents: 450 }] });
    assert.equal(answer.status, 200);
    assert.deepEqual(answer.body, { subtotal: 900, discount: 90, tax: 162, total: 972, currency: 'EUR', display: '€9.72' });
  });
});

test('an unknown region or coupon and an empty order are 400', async () => {
  await withServer(async (base) => {
    assert.equal((await post(base, { region: 'MARS', items: [{ sku: 'A-1', qty: 1, unitCents: 1 }] })).status, 400);
    assert.deepEqual(await post(base, { region: 'US', coupon: 'FREE', items: [{ sku: 'A-1', qty: 1, unitCents: 1 }] }), { status: 400, body: { error: 'unknown coupon FREE' } });
    assert.equal((await post(base, { region: 'US', items: [] })).status, 400);
  });
});
