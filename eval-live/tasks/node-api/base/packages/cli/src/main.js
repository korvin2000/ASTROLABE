import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { formatMoney, quote, region } from '../../pricing/src/index.js';

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
    parsed = parseArgs({ args: argv, options: { region: { type: 'string', default: 'US' } }, allowPositionals: true });
  } catch (error) {
    return { code: 2, out: `${error.message}\n` };
  }
  try {
    const items = parsed.positionals.map(parseItem);
    if (items.length === 0) return { code: 2, out: 'usage: main.js [--region US|EU|UK] SKU:QTY:UNIT_CENTS...\n' };
    const total = quote(items, parsed.values.region);
    return { code: 0, out: `Total: ${formatMoney(total, region(parsed.values.region).currency)}\n` };
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
