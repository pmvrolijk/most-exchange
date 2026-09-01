/**
 * Prices and quantities are fixed-point int64 with 8 implied decimals, everywhere, in every
 * language that touches this system. Formatting divides for display only — nothing here feeds
 * arithmetic back into the API, and no price is ever parsed into a JavaScript number for anything
 * but rendering.
 */
const IMPLIED_DECIMALS = 8
const SCALE = 10 ** IMPLIED_DECIMALS

export function price(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return (value / SCALE).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: IMPLIED_DECIMALS,
  })
}

export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return (value / SCALE).toLocaleString(undefined, { maximumFractionDigits: IMPLIED_DECIMALS })
}

/** Server timestamps are ISO-8601 strings; the operator wants them in their own zone. */
export function at(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}
