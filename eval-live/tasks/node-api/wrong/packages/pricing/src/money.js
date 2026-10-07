const SYMBOLS = { USD: '$', EUR: '€', GBP: '£' };

/** `1250, 'EUR'` → `€12.50`; a negative amount keeps its minus sign in front. */
export function formatMoney(cents, currency) {
  if (!Number.isInteger(cents)) throw new TypeError('formatMoney takes integer cents');
  if (typeof currency !== 'string') throw new TypeError('formatMoney needs a currency');
  const sign = cents < 0 ? '-' : '';
  const symbol = SYMBOLS[currency];
  const abs = Math.abs(cents);
  const amount = `${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
  return symbol ? `${sign}${symbol}${amount}` : `${sign}${amount} ${currency}`;
}
