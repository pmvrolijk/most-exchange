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

/**
 * Quantities are NOT scaled, whatever the blanket statement about "prices and quantities" in the
 * design notes says. `PriceCodec.format` is applied to prices throughout the Kotlin, and the CLI
 * prints `last <price> x <qty>` with the quantity raw -- an order for 10 lots matches 4 lots and
 * the engine reports 4, not 4e-8. Dividing here rendered a trade of 4 as 0.00000004.
 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return value.toLocaleString()
}

/** Server timestamps are ISO-8601 strings; the operator wants them in their own zone. */
export function at(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}

/**
 * A decimal an operator typed, as the int64 the API expects.
 *
 * Scaled by string manipulation, not by multiplying a float: `1.1 * 1e8` is 110000000.00000001 in
 * IEEE 754, and a tick size one unit out is the kind of thing that is never noticed until a book
 * misprices. Digits beyond the eighth are refused rather than rounded — silently discarding what
 * someone typed into a price field is not a service.
 */
export function parsePrice(text: string): number {
  const trimmed = text.trim()
  if (!/^\d+(\.\d+)?$/.test(trimmed)) throw new Error(`not a price: "${text}"`)
  const [whole, fraction = ''] = trimmed.split('.')
  if (fraction.length > IMPLIED_DECIMALS) {
    throw new Error(`a price carries at most ${IMPLIED_DECIMALS} decimals: "${text}"`)
  }
  const scaled = Number(whole + fraction.padEnd(IMPLIED_DECIMALS, '0'))
  if (!Number.isSafeInteger(scaled)) throw new Error(`price out of range: "${text}"`)
  return scaled
}

/**
 * The inverse, for putting an existing value back into an input. Plain digits and a point: a
 * locale-formatted string with thousands separators is not something `parsePrice` accepts back.
 */
export function priceInput(value: number | null | undefined): string {
  if (value === null || value === undefined) return ''
  const negative = value < 0
  const digits = String(Math.abs(value)).padStart(IMPLIED_DECIMALS + 1, '0')
  const whole = digits.slice(0, -IMPLIED_DECIMALS)
  const fraction = digits.slice(-IMPLIED_DECIMALS).replace(/0+$/, '')
  return `${negative ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}`
}

/** Trading dates are YYYYMMDD int32 on the wire, and 0 means GTC. Today, in the operator's zone. */
export function todayTradingDate(): number {
  const now = new Date()
  return now.getFullYear() * 10000 + (now.getMonth() + 1) * 100 + now.getDate()
}
