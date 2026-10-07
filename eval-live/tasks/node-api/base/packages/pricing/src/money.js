const SYMBOLS = { USD: '$', EUR: '€', GBP: '£' };

/** `12.5, 'EUR'` → `€12.50`; a negative amount keeps its minus sign in front. */
export function formatMoney(dollars, currency = 'USD') {
  const sign = dollars < 0 ? '-' : '';
  const symbol = SYMBOLS[currency];
  const amount = Math.abs(dollars).toFixed(2);
  return symbol ? `${sign}${symbol}${amount}` : `${sign}${amount} ${currency}`;
}
