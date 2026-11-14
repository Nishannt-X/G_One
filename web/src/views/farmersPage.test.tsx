import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { seedFarmers } from '../dev/seedFarmers'
import { callOutcome, cropSummary, forgetSeen, mergeSeen, parseReceipt, placeOf, readSeen, toFarmer, type Farmer, type SeenCaller } from '../lib/farmers'
import { rupees } from '../lib/format'
import { forgetNotice } from '../lib/memory'
import { FarmerDetail, FarmerRow } from './FarmersPage'

// Synthetic farmers only.
const NOW = 1_794_132_000_000
const farmers = seedFarmers(NOW)
const byKey = (c: string) => farmers.find((f) => f.callerKey.startsWith(c.repeat(2))) as Farmer
const rich = byKey('a1')
const bengali = byKey('b2')
const tamil = byKey('e5')
const sparse = byKey('f6')
const nameless = byKey('a7')

const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/&#x27;/g, "'").replace(/&amp;/g, '&').replace(/\s+/g, ' ')
const detail = (f: Farmer) => text(renderToStaticMarkup(<FarmerDetail farmer={f} deals={f.deals ?? []} onClose={() => {}} onForget={() => {}} />))
const row = (f: Farmer) => text(renderToStaticMarkup(<ul><FarmerRow farmer={f} /></ul>))
const NATIVE = /[ऀ-ൿ]/

describe('Farmers tab: rows', () => {
  test('a row shows the English name, place, language, at most three crops, last call, deals and follow-ups', () => {
    const r = row(rich)
    expect(r).toContain('Rameshwar Patil')
    expect(r).toContain('Nashik, Maharashtra')
    expect(r).toContain('Hindi')
    expect(r).toContain('red onion')
    expect(r).toContain('+1')
    expect(r).not.toContain('garlic')
    expect(r).toContain('2 deals')
    expect(r).toContain('1 follow-up')
    expect(r).toContain('1 open')
    expect(r).not.toMatch(NATIVE)
  })

  test('native-script names show in English letters, with an English initial', () => {
    expect(row(bengali)).toContain('Mita Das')
    expect(row(tamil)).toContain('Selvi')
    const html = renderToStaticMarkup(<ul><FarmerRow farmer={bengali} /></ul>)
    expect(html).toMatch(/rounded-full[^>]*>M</)
    for (const f of farmers) expect(row(f)).not.toMatch(NATIVE)
    expect(html).not.toContain('lang=')
  })

  test('a farmer without a name or deals still reads cleanly', () => {
    expect(row(nameless)).toContain('Name not given')
    expect(row(byKey('c3'))).toContain('0 deals')
    expect(row(byKey('c3'))).toContain('0 follow-ups')
  })
})

describe('Farmers tab: card detail', () => {
  test('a detail is made of the six cards and the forget footer', () => {
    const d = detail(rich)
    for (const title of ['Profile', 'What they sell', 'How they work', 'Deals', 'Calls', 'Follow-ups']) {
      expect(renderToStaticMarkup(<FarmerDetail farmer={rich} deals={rich.deals ?? []} onClose={() => {}} onForget={() => {}} />)).toContain(`aria-label="${title}"`)
    }
    expect(d).toContain('Forget this farmer')
    expect(d).not.toMatch(NATIVE)
  })

  test('profile and how-they-work fields carry their values and provenance', () => {
    const d = detail(rich)
    expect(d).toContain('Rameshwar Patil')
    expect(d).toContain('Pimpalgaon')
    expect(d).toContain('Lasalgaon APMC')
    expect(d).toContain('40 quintal per sale')
    expect(d).toContain('Needs truck')
    expect(d).toContain('UPI')
    expect(d).toContain('Cold storage nearby')
    expect(d).toContain('Told')
    expect(d).toContain('Inferred')
    expect(d).not.toContain('Not told yet')
  })

  test('deals show the price with its basis, the buyer and the state', () => {
    const d = detail(rich)
    expect(d).toContain('10 quintal red onion')
    expect(d).toContain(text(`${rupees(2200)} per quintal`))
    expect(d).toContain('Trader, Pune')
    expect(d).toContain('Agreed')
    expect(detail(bengali)).toContain(text(`${rupees(26000)} total`))
    expect(detail(byKey('d4'))).toContain('offer open')
  })

  test('calls are a compact log with an outcome and a labelled short note', () => {
    const d = detail(rich)
    expect(d).toContain("G-One's short note, not a transcript")
    expect(d).toContain('Short note:')
    expect(d).toContain('Deal agreed')
    expect(d).toContain('No deal')
    expect(d).toContain('6:00')
    expect(d).toContain('Check the pickup and UPI payment')
  })

  test('missing data says "Not told yet", never a blank', () => {
    const d = detail(sparse)
    expect(d).toContain('Basavaraj')
    expect(d).toContain('Kannada')
    expect(d.match(/Not told yet/g)?.length).toBe(9)
    expect(d).toContain('No recorded deals yet.')
    expect(d).toContain('Nothing open.')
    expect(detail(nameless)).toContain('Name not given')
  })

  test('a native-script name is shown in English letters in the card and the forget notice', () => {
    expect(detail(bengali)).toContain('Mita Das')
    expect(detail(tamil)).toContain('Selvi')
    expect(forgetNotice(bengali.name ?? '', 'done')).toBe('Mita Das has been forgotten.')
  })
})

describe('Farmers tab: data', () => {
  test('profile values from stored facts: language codes, English letters, crops with provenance', () => {
    const f = toFarmer('a'.repeat(64), 'memory', 'hi-IN', 5, [
      { field: 'name', value: 'रमेश पाटिल', source: 'said', callId: 'c', atMs: 1 },
      { field: 'language', value: 'HI_EN', source: 'inferred', callId: 'c', atMs: 1 },
      { field: 'crops', value: 'onion, टमाटर', source: 'said', callId: 'c', atMs: 1 },
      { field: 'district', value: 'नासिक', source: 'said', callId: 'c', atMs: 1 },
    ], [])
    expect(f.name).toBe('Ramesh Patil')
    expect(f.language).toBe('hi-en')
    expect(f.district).toBe('Nasik')
    expect(f.cropFacts.map((c) => [c.name, c.source])).toEqual([['onion', 'said'], ['Tamatar', 'said']])
    expect(placeOf(f)).toBe('Nasik')
    expect(cropSummary(['a', 'b', 'c', 'd', 'e'])).toEqual({ shown: ['a', 'b', 'c'], more: 2 })
  })

  test('receipts become deals; a call outcome comes from its deal', () => {
    const d = parseReceipt({ id: 'r1', state: 'agreed', commodity: 'onion', quantity: '10 quintal', price: 2100, unit: 'quintal', market: 'Lasalgaon', atMs: 9 }, 'call-1')
    expect(d).toMatchObject({ id: 'r1', callId: 'call-1', price: 2100, market: 'Lasalgaon' })
    expect(parseReceipt({ error: 'not_found' }, 'call-2')).toBeNull()
    expect(callOutcome('call-1', d ? [d] : []).label).toBe('Deal agreed')
    expect(callOutcome('call-9', d ? [d] : []).label).toBe('No deal')
  })

  test('forget still removes the farmer from this browser list', () => {
    const store = new Map<string, string>()
    const previous = globalThis.localStorage
    globalThis.localStorage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k), clear: () => store.clear(), key: () => null, length: 0 } as Storage
    try {
      const seen = (callerKey: string): SeenCaller => ({ callerKey, language: 'hi-IN', seenMs: 1, origin: 'memory' })
      store.set('g-one-seen-callers', JSON.stringify(mergeSeen([seen('a'.repeat(64))], seen('b'.repeat(64)))))
      forgetSeen('a'.repeat(64))
      expect(readSeen().map((c) => c.callerKey)).toEqual(['b'.repeat(64)])
    } finally {
      globalThis.localStorage = previous
    }
  })
})
