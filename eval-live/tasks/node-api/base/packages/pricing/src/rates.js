// Tax per region in basis points (1/100 of a percent), and the currency a region is quoted in.
export const REGIONS = Object.freeze({
  US: Object.freeze({ taxBasisPoints: 700, currency: 'USD' }),
  EU: Object.freeze({ taxBasisPoints: 2000, currency: 'EUR' }),
  UK: Object.freeze({ taxBasisPoints: 2000, currency: 'GBP' }),
});

export function region(code) {
  const found = REGIONS[code];
  if (!found) throw new RangeError(`unknown region ${code}`);
  return found;
}
