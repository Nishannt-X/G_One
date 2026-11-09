// Development only: FarmersPage imports this behind `import.meta.env.DEV` and #/farmers?seed=1. Every person, place and
// number here is invented, and nothing here is sent to the backend.
import { toFarmer, type Farmer } from '../lib/farmers'
import type { MemoryFact } from '../lib/memory'

const DAY = 86_400_000

function facts(atMs: number, rows: [string, string, 'said' | 'inferred'][]): MemoryFact[] {
  return rows.map(([field, value, source]) => ({ field, value, source, callId: 'seed-call', atMs }))
}

export function seedFarmers(now: number): Farmer[] {
  const key = (c: string) => c.repeat(64).slice(0, 64)
  return [
    toFarmer(key('a1'), 'seed', 'hi-IN', now - DAY, facts(now - DAY, [['name', 'रामेश्वर पाटिल', 'said'], ['district', 'Nashik', 'said'], ['crops', 'onion, tomato', 'said'], ['usualMandi', 'Lasalgaon', 'inferred']]), [
      { callId: 'seed-1', startedMs: now - DAY, endedMs: now - DAY + 240_000, summary: 'Sold ten quintals of onion at ₹22 a kilo. Pickup agreed for the next morning.', followUps: ['Ask whether the pickup went as agreed'] },
      { callId: 'seed-2', startedMs: now - 9 * DAY, endedMs: now - 9 * DAY + 120_000, summary: 'Asked the onion rate at Lasalgaon. Decided to wait for a better price.', followUps: [] },
    ], [{ id: 'seed-1', commodity: 'onion', quantity: '10 quintal', price: 22, unit: 'kg', pickup: 'farm, next morning', state: 'agreed', atMs: now - DAY }]),
    toFarmer(key('b2'), 'seed', 'bn-IN', now - 3 * DAY, facts(now - 3 * DAY, [['name', 'মিতা দাস', 'said'], ['district', 'Bardhaman', 'said'], ['crops', 'potato', 'said']]), [
      { callId: 'seed-3', startedMs: now - 3 * DAY, endedMs: now - 3 * DAY + 180_000, summary: 'Wanted a buyer for potatoes. A caution was given when a caller asked for an OTP.', followUps: ['Share the next dated potato reference'] },
    ], []),
    toFarmer(key('c3'), 'seed', 'hi-en', now - 12 * DAY, facts(now - 12 * DAY, [['name', 'Gurpreet Singh', 'said'], ['district', 'Ludhiana', 'inferred'], ['crops', 'wheat', 'said']]), [
      { callId: 'seed-4', startedMs: now - 12 * DAY, endedMs: now - 12 * DAY + 90_000, summary: 'Asked about weather before harvest. No sale discussed.', followUps: [] },
    ], []),
  ]
}
