/** One entry in the buyer directory, as the page needs it. Every buyer is synthetic. */
export interface Buyer {
  id: string
  name: string
  crops: string[]
  district: string
  language: string
  priceMin: number | null
  priceMax: number | null
  unit: string
}

export type BuyerSort = 'name' | 'price_high' | 'price_low'

export interface BuyerFilter {
  search: string
  crop: string
  district: string
  sort: BuyerSort
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function amount(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function strings(value: unknown): string[] {
  if (typeof value === 'string') return value.split(',').map((s) => s.trim()).filter(Boolean)
  if (!Array.isArray(value)) return []
  // The directory lists each crop as an object with its own price range.
  return value.flatMap((s) => (typeof s === 'string' ? [s] : text((s as { crop?: unknown } | null)?.crop) || []))
}

/** The lowest minimum and highest maximum across a buyer's crop entries, for a one-line range on the card. */
function cropRange(value: unknown): { min: number | null; max: number | null; unit: string } {
  const entries = (Array.isArray(value) ? value : []).filter((c): c is Record<string, unknown> => typeof c === 'object' && c !== null)
  const mins = entries.map((c) => amount(c.minPrice)).filter((n): n is number => n !== null)
  const maxes = entries.map((c) => amount(c.maxPrice)).filter((n): n is number => n !== null)
  return { min: mins.length ? Math.min(...mins) : null, max: maxes.length ? Math.max(...maxes) : null, unit: text(entries[0]?.unit) }
}

/** Reads the directory endpoint's answer. Tolerates a bare array or a wrapper, and skips entries with no name. */
export function parseBuyers(body: unknown): Buyer[] {
  const list = Array.isArray(body) ? body : (body as { buyers?: unknown } | null)?.buyers
  if (!Array.isArray(list)) return []
  return list.flatMap((entry, index) => {
    if (typeof entry !== 'object' || entry === null) return []
    const b = entry as Record<string, unknown>
    const name = text(b.name)
    if (!name) return []
    const crops = cropRange(b.crops)
    const range = (typeof b.priceRange === 'object' && b.priceRange !== null ? b.priceRange : {}) as Record<string, unknown>
    return [
      {
        id: text(b.id) || text(b.key) || `${name}-${index}`,
        name,
        crops: strings(b.crops ?? b.cropsWanted ?? b.commodities),
        district: text(b.district),
        language: text(b.language),
        priceMin: amount(b.priceMin ?? b.minPrice ?? range.min) ?? crops.min,
        priceMax: amount(b.priceMax ?? b.maxPrice ?? range.max) ?? crops.max,
        unit: text(b.unit ?? range.unit) || crops.unit || 'quintal',
      },
    ]
  })
}

export function filterBuyers(buyers: Buyer[], filter: BuyerFilter): Buyer[] {
  const needle = filter.search.trim().toLowerCase()
  const kept = buyers.filter(
    (b) =>
      (!filter.crop || b.crops.includes(filter.crop)) &&
      (!filter.district || b.district === filter.district) &&
      (!needle || [b.name, b.district, ...b.crops].some((s) => s.toLowerCase().includes(needle))),
  )
  const price = (b: Buyer) => b.priceMax ?? b.priceMin
  return [...kept].sort((a, b) => {
    if (filter.sort !== 'name') {
      const [x, y] = [price(a), price(b)]
      // A buyer with no stated price goes last whichever way the list is sorted.
      if (x !== y) {
        if (x === null) return 1
        if (y === null) return -1
        return filter.sort === 'price_high' ? y - x : x - y
      }
    }
    return a.name.localeCompare(b.name)
  })
}

export function uniqueSorted(values: string[]): string[] {
  return [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b))
}
