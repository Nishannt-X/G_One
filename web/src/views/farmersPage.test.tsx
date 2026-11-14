import { describe, expect, test } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { seedFarmers } from '../dev/seedFarmers'
import { callOutcome, cropSummary, forgetSeen, mergeSeen, parseReceipt, placeOf, readSeen, toFarmer, type Farmer, type SeenCaller } from '../lib/farmers'
import { callRows } from '../lib/farmersOverlay'
import { rupees, shortDate } from '../lib/format'
import { forgetNotice } from '../lib/memory'
import { CallDetail, FarmerDetail, FarmerRow } from './FarmersPage'

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
const detail = (f: Farmer) => text(renderToStaticMarkup(<FarmerDetail farmer={f} deals={f.deals ?? []} onClose={() => {}} onForget={() => {}} onOpenCall={() => {}} />))
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

  test('a row links to its overlay, keeps the dev seed on, and is the focus-return target', () => {
    const html = renderToStaticMarkup(<ul><FarmerRow farmer={rich} seed /></ul>)
    expect(html).toContain(`href="#/farmers/${rich.callerKey}?seed=1"`)
    expect(html).toContain(`data-farmer-row="${rich.callerKey}"`)
    expect(renderToStaticMarkup(<ul><FarmerRow farmer={rich} /></ul>)).toContain(`href="#/farmers/${rich.callerKey}"`)
  })

  test('a farmer without a name or deals still reads cleanly', () => {
    expect(row(nameless)).toContain('Name not given')
    expect(row(byKey('c3'))).toContain('0 deals')
    expect(row(byKey('c3'))).toContain('0 follow-ups')
  })
})

describe('Farmers tab: farmer overlay', () => {
  test('the overlay has a header, the inferred profile in four groups, the calls and the forget footer', () => {
    const html = renderToStaticMarkup(<FarmerDetail farmer={rich} deals={rich.deals} onClose={() => {}} onForget={() => {}} onOpenCall={() => {}} />)
    for (const group of ['Identity', 'Produce', 'Selling', 'Logistics']) expect(html).toContain(`aria-label="${group}"`)
    expect(html).toContain('id="farmer-overlay-title"')
    expect(html).toContain('data-autofocus')
    const d = detail(rich)
    expect(d).toContain('Rameshwar Patil')
    expect(d).toContain('Caller ••••a1a1 · Hindi')
    expect(d).toContain('Synthetic')
    expect(d).toContain('3 calls')
    expect(d).toContain('Profile completeness 11 of 11 known')
    expect(d).toContain('Forget this farmer')
    expect(d).not.toMatch(NATIVE)
    expect(html).not.toContain('animate-drawer')
  })

  test('each known variable carries its value, Told or Inferred, and when it was last confirmed', () => {
    const d = detail(rich)
    expect(d).toContain('Pimpalgaon')
    expect(d).toContain('Lasalgaon APMC')
    expect(d).toContain('40 quintal per sale')
    expect(d).toContain('Needs truck')
    expect(d).toContain('UPI')
    expect(d).toContain('Cold storage nearby')
    expect(d).toContain('red onion, tomato, grapes (inferred), garlic')
    expect(d).toContain('Told')
    expect(d).toContain('Inferred')
    expect(d).toContain(`Last confirmed ${shortDate(NOW - 86_400_000)}`)
    expect(d).not.toContain('Not told yet')
  })

  test('missing variables say "Not told yet" and the completeness counts only what is known', () => {
    const d = detail(sparse)
    expect(d).toContain('Basavaraj')
    expect(d).toContain('Kannada')
    expect(d).toContain('Profile completeness 2 of 11 known')
    expect(d.match(/Not told yet/g)?.length).toBe(9)
    expect(detail(nameless)).toContain('Name not given')
  })

  test('calls are compact rows: date and time, length, language, outcome, deal line and follow-ups', () => {
    const html = renderToStaticMarkup(<FarmerDetail farmer={rich} deals={rich.deals} onClose={() => {}} onForget={() => {}} onOpenCall={() => {}} />)
    expect(html.match(/<button type="button" data-call-row=/g)?.length).toBe(3)
    const d = detail(rich)
    expect(d).toContain('6:00')
    expect(d).toContain('Hindi')
    expect(d).toContain('Deal agreed')
    expect(d).toContain('No deal')
    expect(d).toContain('10 quintal red onion · 1 follow-up')
    expect(d).toContain('0 follow-ups')
    // The long note and the price stay in the call overlay.
    expect(d).not.toContain('Pune trader, pickup at the farm')
    expect(d).not.toContain(text(rupees(2200)))
  })

  test('while deals are being looked up the outcome says so', () => {
    const d = text(renderToStaticMarkup(<FarmerDetail farmer={rich} deals={undefined} onClose={() => {}} onForget={() => {}} onOpenCall={() => {}} />))
    expect(d).toContain('Checking deal…')
  })

  test('a native-script name is shown in English letters in the overlay and the forget notice', () => {
    expect(detail(bengali)).toContain('Mita Das')
    expect(detail(tamil)).toContain('Selvi')
    expect(forgetNotice(bengali.name ?? '', 'done')).toBe('Mita Das has been forgotten.')
  })
})

describe('Farmers tab: call overlay', () => {
  const call = (f: Farmer, callId: string, checking = false) => {
    const row = callRows(f, checking ? undefined : f.deals).find((r) => r.callId === callId)
    if (!row) throw new Error(callId)
    return text(renderToStaticMarkup(<CallDetail farmer={f} row={row} checking={checking} onClose={() => {}} />))
  }

  test('only the basics: when, length, language, outcome, the deal with its price basis and buyer', () => {
    const c = call(rich, 'seed-1')
    expect(c).toContain('Rameshwar Patil')
    expect(c).toContain('6:00')
    expect(c).toContain('Hindi')
    expect(c).toContain('Deal agreed')
    expect(c).toContain('Red onion')
    expect(c).toContain('10 quintal')
    expect(c).toContain(text(`${rupees(2200)} per quintal`))
    expect(c).toContain('Trader, Pune')
    expect(c).toContain('Check the pickup and UPI payment')
    expect(call(bengali, 'seed-3')).toContain(text(`${rupees(26000)} total`))
    expect(call(byKey('d4'), 'seed-5')).toContain('Deal offer open')
  })

  test("the note is labelled as a short note, and the call links to Analytics", () => {
    const html = renderToStaticMarkup(<CallDetail farmer={rich} row={callRows(rich, rich.deals)[0]} onClose={() => {}} />)
    expect(text(html)).toContain('short note, not a transcript')
    expect(text(html)).toContain('Agreed 10 quintal red onion with a Pune trader')
    expect(html).toContain('href="#/analytics/calls?id=seed-1"')
    expect(html).toContain('id="call-overlay-title"')
  })

  test('a call without a deal or follow-ups says so', () => {
    const c = call(rich, 'seed-2')
    expect(c).toContain('No deal was recorded on this call.')
    expect(c).toContain('None.')
    expect(call(rich, 'seed-1', true)).not.toContain('No deal was recorded')
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
