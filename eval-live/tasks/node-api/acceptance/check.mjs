// Hidden checks of node-api, run by accept.py as `node _acceptance/check.mjs` from the root of the workspace copy.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const pricing = await import(new URL('../packages/pricing/src/index.js', import.meta.url));
const api = await import(new URL('../packages/api/src/index.js', import.meta.url));

const ITEMS = [
  { sku: 'A-1', qty: 3, unitCents: 333 },
  { sku: 'B-7', qty: 1, unitCents: 1255 },
];

test('quote answers a breakdown in cents', () => {
  assert.deepEqual(pricing.quote(ITEMS, { region: 'US' }), { subtotal: 2254, discount: 0, tax: 158, total: 2412, currency: 'USD' });
  assert.deepEqual(pricing.quote(ITEMS, { region: 'UK', coupon: null }), { subtotal: 2254, discount: 0, tax: 451, total: 2705, currency: 'GBP' });
});

test('SAVE10 rounds half up and comes off before the tax', () => {
  assert.deepEqual(pricing.quote([{ sku: 'X', qty: 1, unitCents: 1005 }], { region: 'EU', coupon: 'SAVE10' }),
    { subtotal: 1005, discount: 101, tax: 181, total: 1085, currency: 'EUR' });
  assert.deepEqual(pricing.quote(ITEMS, { region: 'EU', coupon: 'SAVE10' }), { subtotal: 2254, discount: 225, tax: 406, total: 2435, currency: 'EUR' });
});

test('FLAT5 never takes more than the subtotal', () => {
  assert.deepEqual(pricing.quote(ITEMS, { region: 'US', coupon: 'FLAT5' }), { subtotal: 2254, discount: 500, tax: 123, total: 1877, currency: 'USD' });
  assert.deepEqual(pricing.quote([{ sku: 'X', qty: 1, unitCents: 320 }], { region: 'UK', coupon: 'FLAT5' }),
    { subtotal: 320, discount: 320, tax: 0, total: 0, currency: 'GBP' });
});

test('unknown coupon, unknown region, missing options', () => {
  assert.throws(() => pricing.quote(ITEMS, { region: 'US', coupon: 'save10' }), (e) => e instanceof RangeError && e.message === 'unknown coupon save10');
  assert.throws(() => pricing.quote(ITEMS, { region: 'US', coupon: 'toString' }), RangeError);
  assert.throws(() => pricing.quote(ITEMS, { region: 'MARS' }), RangeError);
  assert.throws(() => pricing.quote(ITEMS), TypeError);
  assert.throws(() => pricing.quote(ITEMS, {}), TypeError);
  assert.throws(() => pricing.quote(ITEMS, 'US'), TypeError);
});

test('formatMoney takes cents and a currency', () => {
  assert.equal(pricing.formatMoney(1250, 'EUR'), '€12.50');
  assert.equal(pricing.formatMoney(-90, 'EUR'), '-€0.90');
  assert.equal(pricing.formatMoney(5, 'USD'), '$0.05');
  assert.equal(pricing.formatMoney(123456, 'CHF'), '1234.56 CHF');
  assert.throws(() => pricing.formatMoney(1250));
});

async function withServer(run) {
  const server = api.createQuoteServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    await run(`http://127.0.0.1:${server.address().port}/quote`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function post(url, body) {
  const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  return { status: response.status, body: await response.json() };
}

test('POST /quote: breakdown, coupon and display', async () => {
  await withServer(async (url) => {
    assert.deepEqual(await post(url, { region: 'EU', coupon: 'SAVE10', items: ITEMS }), {
      status: 200,
      body: { subtotal: 2254, discount: 225, tax: 406, total: 2435, currency: 'EUR', display: '€24.35' },
    });
    assert.deepEqual(await post(url, { region: 'US', items: ITEMS }), {
      status: 200,
      body: { subtotal: 2254, discount: 0, tax: 158, total: 2412, currency: 'USD', display: '$24.12' },
    });
  });
});

test('POST /quote: errors are 400 with the message', async () => {
  await withServer(async (url) => {
    assert.deepEqual(await post(url, { region: 'US', coupon: 'BOGUS', items: ITEMS }), { status: 400, body: { error: 'unknown coupon BOGUS' } });
    assert.equal((await post(url, { region: 'MARS', items: ITEMS })).status, 400);
    assert.equal((await post(url, { region: 'US', items: [] })).status, 400);
  });
});

function cli(...args) {
  const result = spawnSync(process.execPath, [join(ROOT, 'packages', 'cli', 'src', 'main.js'), ...args], { cwd: ROOT, encoding: 'utf8', timeout: 30000 });
  return { code: result.status, out: result.stdout.replace(/\r\n/g, '\n') };
}

test('the command line prints the breakdown', () => {
  assert.deepEqual(cli('--region', 'EU', '--coupon', 'SAVE10', 'A-1:3:333', 'B-7:1:1255'), {
    code: 0,
    out: 'Subtotal: €22.54\nDiscount: -€2.25\nTax: €4.06\nTotal: €24.35\n',
  });
  assert.deepEqual(cli('--region', 'UK', 'A-1:3:333', 'B-7:1:1255'), { code: 0, out: 'Subtotal: £22.54\nTax: £4.51\nTotal: £27.05\n' });
  assert.deepEqual(cli('--coupon', 'FLAT5', 'X:1:320'), { code: 0, out: 'Subtotal: $3.20\nDiscount: -$3.20\nTax: $0.00\nTotal: $0.00\n' });
  const bad = cli('--coupon', 'BOGUS', 'X:1:320');
  assert.equal(bad.code, 1);
  assert.match(bad.out, /unknown coupon BOGUS/);
});

test('still no dependencies', () => {
  assert.equal(existsSync(join(ROOT, 'node_modules')), false, 'no node_modules');
  for (const manifest of ['package.json', 'packages/pricing/package.json', 'packages/api/package.json', 'packages/cli/package.json']) {
    const parsed = JSON.parse(readFileSync(join(ROOT, manifest), 'utf8'));
    for (const key of ['dependencies', 'devDependencies']) {
      assert.equal(Object.keys(parsed[key] ?? {}).length, 0, `${manifest} has ${key}`);
    }
  }
});
