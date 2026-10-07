import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { formatMoney, quote } from '../../pricing/src/index.js';

/** `SKU:QTY:UNIT_CENTS` → an item. */
export function parseItem(text) {
  const [sku, qty, unitCents] = text.split(':');
  const item = { sku, qty: Number(qty), unitCents: Number(unitCents) };
  if (!sku || !Number.isInteger(item.qty) || !Number.isInteger(item.unitCents)) throw new RangeError(`bad item ${text}`);
  return item;
}

/** Runs the command line on `argv`; returns the exit code and what it prints. */
export function run(argv) {
  let parsed;
  try {
    parsed = parseArgs({
      args: argv,
      options: { region: { type: 'string', default: 'US' }, coupon: { type: 'string' } },
      allowPositionals: true,
    });
  } catch (error) {
    return { code: 2, out: `${error.message}\n` };
  }
  try {
    const items = parsed.positionals.map(parseItem);
    if (items.length === 0) return { code: 2, out: 'usage: main.js [--region US|EU|UK] [--coupon CODE] SKU:QTY:UNIT_CENTS...\n' };
    const q = quote(items, { region: parsed.values.region, coupon: parsed.values.coupon });
    const lines = [`Subtotal: ${formatMoney(q.subtotal, q.currency)}`];
    if (q.discount !== 0) lines.push(`Discount: ${formatMoney(-q.discount, q.currency)}`);
    lines.push(`Tax: ${formatMoney(q.tax, q.currency)}`, `Total: ${formatMoney(q.total, q.currency)}`);
    return { code: 0, out: `${lines.join('\n')}\n` };
  } catch (error) {
    if (error instanceof RangeError) return { code: 1, out: `error: ${error.message}\n` };
    throw error;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { code, out } = run(process.argv.slice(2));
  process.stdout.write(out);
  process.exitCode = code;
}
