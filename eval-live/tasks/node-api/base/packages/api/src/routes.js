import { formatMoney, quote, region } from '../../pricing/src/index.js';

/** `POST /quote` with `{ items, region }`: the price, tax included, and how it is shown. */
export function postQuote(body) {
  if (!body || !Array.isArray(body.items) || body.items.length === 0) {
    return { status: 400, body: { error: 'no items' } };
  }
  try {
    const total = quote(body.items, body.region);
    return { status: 200, body: { total, display: formatMoney(total, region(body.region).currency) } };
  } catch (error) {
    if (error instanceof RangeError) return { status: 400, body: { error: error.message } };
    throw error;
  }
}
