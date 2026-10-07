import { formatMoney, quote } from '../../pricing/src/index.js';

/** `POST /quote` with `{ items, region }`: the breakdown in cents and how the total is shown. */
export function postQuote(body) {
  if (!body || !Array.isArray(body.items) || body.items.length === 0) {
    return { status: 400, body: { error: 'no items' } };
  }
  try {
    const breakdown = quote(body.items, { region: body.region });
    return { status: 200, body: { ...breakdown, display: formatMoney(breakdown.total, breakdown.currency) } };
  } catch (error) {
    if (error instanceof RangeError || error instanceof TypeError) return { status: 400, body: { error: error.message } };
    throw error;
  }
}
