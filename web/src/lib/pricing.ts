import type { CallView, ToolView } from './callState'

/** A dated reference taken from the mandi tool's answer. Never call this today's price or a fair price. */
export interface PriceReference {
  price: number
  unit: string
  perQuintal: number
  source: string | null
  crop: string | null
  market: string | null
  date: string | null
  raw: string
}

const UNIT_KG: Record<string, number> = {
  kg: 1,
  kgs: 1,
  kilo: 1,
  kilos: 1,
  kilogram: 1,
  kilograms: 1,
  quintal: 100,
  quintals: 100,
  qtl: 100,
  ton: 1000,
  tons: 1000,
  tonne: 1000,
  tonnes: 1000,
}

const UNITS = Object.keys(UNIT_KG).sort((a, b) => b.length - a.length).join('|')

const SMALL: Record<string, number> = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11,
  twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19,
  twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90,
}
const LARGE: Record<string, number> = { thousand: 1_000, lakh: 100_000, lakhs: 100_000, crore: 10_000_000 }

function isNumberWord(word: string): boolean {
  return word in SMALL || word in LARGE || word === 'hundred' || word === 'and'
}

/** "four thousand five hundred" becomes 4500. Null when the words are not a number. */
export function wordsToNumber(text: string): number | null {
  const words = text.toLowerCase().split(/[\s-]+/).filter((w) => w && w !== 'and')
  if (words.length === 0) return null
  let total = 0
  let current = 0
  for (const word of words) {
    if (word in SMALL) current += SMALL[word]
    else if (word === 'hundred') current = (current || 1) * 100
    else if (word in LARGE) {
      total += (current || 1) * LARGE[word]
      current = 0
    } else return null
  }
  return total + current
}

/** The last run of number words before `end`, so "listed four thousand" gives "four thousand". */
function trailingNumber(text: string): number | null {
  const words = text.trim().split(/[\s-]+/)
  let from = words.length
  while (from > 0 && isNumberWord(words[from - 1].toLowerCase())) from--
  return from === words.length ? null : wordsToNumber(words.slice(from).join(' '))
}

function perQuintal(price: number, unit: string): number | null {
  const kg = UNIT_KG[unit.toLowerCase()]
  return kg ? (price / kg) * 100 : null
}

/** Reads a price per unit from the mandi tool's sentence, written with digits or (for the voice) in words. */
export function parseReference(text: string): PriceReference | null {
  let price: number | null = null
  let unit: string | null = null
  const digits = new RegExp(`([\\d,]+(?:\\.\\d+)?)\\s*(?:rupees?\\s*)?(?:per|/|a)\\s*(${UNITS})\\b`, 'i').exec(text)
  if (digits) {
    price = Number(digits[1].replace(/,/g, ''))
    unit = digits[2]
  } else {
    const words = new RegExp(`^(.*?)\\brupees?\\s+(?:per|a)\\s+(${UNITS})\\b`, 'i').exec(text)
    if (words) {
      price = trailingNumber(words[1])
      unit = words[2]
    }
  }
  if (price === null || unit === null || !Number.isFinite(price) || price <= 0) return null
  const quintal = perQuintal(price, unit)
  if (quintal === null) return null
  const source = /^\s*([A-Za-z]+)\s+listed\b/.exec(text)?.[1] ?? null
  const place = /\bfor\s+(.+?)\s+at\s+(.+?)\s+on\s+(.+?)(?:,|\.)\s*(?:a dated|$)/i.exec(text)
  const dated = /\bdated\s+([\w-]+)/i.exec(text)
  const market = place?.[2] ?? /\bat\s+([A-Z][\w-]*)/.exec(text)?.[1] ?? null
  return {
    price,
    unit: unit.toLowerCase(),
    perQuintal: quintal,
    source,
    crop: place?.[1] ?? null,
    market,
    date: place?.[3] ?? dated?.[1] ?? null,
    raw: text,
  }
}

function argText(tool: ToolView, name: string): string {
  const value = tool.args?.[name]
  return typeof value === 'string' ? value : ''
}

function isLatin(text: string): boolean {
  return /^[\x20-\x7e]*$/.test(text)
}

/** The newest dated reference that applies to this crop. A crop named in another script cannot be matched, so any will do. */
export function latestReference(tools: ToolView[], commodity: string | null): PriceReference | null {
  let fallback: PriceReference | null = null
  for (let i = tools.length - 1; i >= 0; i--) {
    const tool = tools[i]
    if (!tool.ok || !tool.tool.toLowerCase().includes('mandi')) continue
    const reference = parseReference(tool.result)
    if (!reference) continue
    if (!commodity) return reference
    const want = commodity.toLowerCase()
    if (`${reference.raw} ${argText(tool, 'commodity')}`.toLowerCase().includes(want)) return reference
    if (!isLatin(commodity)) fallback ??= reference
  }
  return fallback
}

export interface PriceComparison {
  offerPerQuintal: number
  referencePerQuintal: number
  /** Percent above (positive) or below (negative) the dated reference. */
  deltaPct: number
}

export function comparePrice(price: number | null, unit: string | null, reference: PriceReference | null): PriceComparison | null {
  if (price === null || !unit || !reference) return null
  const offer = perQuintal(price, unit)
  if (offer === null) return null
  return { offerPerQuintal: offer, referencePerQuintal: reference.perQuintal, deltaPct: ((offer - reference.perQuintal) / reference.perQuintal) * 100 }
}

/** "10 quintal" or "ten quintals" in kilograms. Null when it cannot be read. */
export function quantityKg(quantity: string | null): number | null {
  if (!quantity) return null
  const match = new RegExp(`^\\s*(.+?)\\s*(${UNITS})\\s*$`, 'i').exec(quantity)
  if (!match) return null
  const amount = /^[\d,.]+$/.test(match[1]) ? Number(match[1].replace(/,/g, '')) : wordsToNumber(match[1])
  if (amount === null || !Number.isFinite(amount)) return null
  return amount * UNIT_KG[match[2].toLowerCase()]
}

/** What the terms add up to, in rupees. Null unless quantity and price can both be read. */
export function dealValue(quantity: string | null, price: number | null, unit: string | null): number | null {
  if (price !== null && unit === 'total') return price
  const kg = quantityKg(quantity)
  const unitKg = unit ? UNIT_KG[unit.toLowerCase()] : undefined
  if (kg === null || price === null || !unitKg) return null
  return (kg / unitKg) * price
}

/** The meter runs this many percent either side of the dated reference. */
export const METER_RANGE_PCT = 25

/** 0..100 along the meter, with anything beyond the range held at the end. */
export function meterPosition(deltaPct: number): number {
  return Math.min(100, Math.max(0, ((deltaPct + METER_RANGE_PCT) / (2 * METER_RANGE_PCT)) * 100))
}

/** The offer against the latest dated mandi reference, when both exist. */
export function priceCheck(call: CallView | null) {
  const deal = call?.deal ?? null
  const reference = call ? latestReference(call.tools, deal?.commodity ?? null) : null
  const comparison = comparePrice(deal?.price ?? null, deal?.unit ?? null, reference)
  const delta = comparison?.deltaPct ?? 0
  const near = Math.abs(delta) < 0.5
  const verdict = comparison ? (near ? 'In line with the dated reference' : `${Math.abs(delta).toFixed(1)}% ${delta > 0 ? 'above' : 'below'} the dated reference`) : null
  return { reference, comparison, delta, verdict }
}
