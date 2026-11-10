import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { callCode, filterCalls, languagesOf, NO_FILTER, tabOf, toCallView, type CallRecordView, type CallSummary } from '../lib/analytics'
import { CallDetail, CallsTable, KpiStrip } from './CallsView'

// Synthetic records only.
const T0 = 1_794_132_000_000

function summary(id: string, over: Partial<CallSummary> = {}): CallSummary {
  return {
    callId: id,
    startedMs: T0,
    endedMs: T0 + 60_000,
    durationMs: 60_000,
    endReason: 'hangup',
    languages: { farmer: 'hi-IN', buyer: 'ta-IN' },
    outcome: 'agreed',
    consentFarmer: 'YES',
    consentBuyer: 'YES',
    deal: { state: 'agreed', commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 2100, unit: 'quintal', pickup: 'Friday', farmerYes: true, buyerYes: true },
    ...over,
  }
}

const timing = (legId: string, role: 'farmer' | 'buyer', at: number) => ({ key: `${legId}:${at}`, legId, role, startedMs: at, endedMs: at + 500, heardMs: at + 900, translatedMs: at + 1300, repliedMs: null })

function record(over: Partial<CallRecordView['data']> = {}, top: Partial<CallRecordView> = {}): CallRecordView {
  const { deal, ...rest } = summary('call-synthetic-1')
  return {
    ...rest,
    createdAt: T0 + 61_000,
    data: {
      deal,
      notStored: {},
      farmer: {
        legId: 'f1', source: 'twilio', language: 'hi-IN', joinedMs: T0, endReason: 'hangup',
        timings: [timing('f1', 'farmer', T0 + 1000)],
        tools: [{ tool: 'get_mandi_price', ok: true, durationMs: 120, atMs: T0 + 2000, result: 'Onion at Lasalgaon: 2,150 rupees per quintal' }, { tool: 'save_profile_fact', ok: true, durationMs: 9, atMs: T0 + 2500 }],
        advice: [{ kind: 'caution', atMs: T0 + 3000 }],
        memory: { returning: true, facts: [{ field: 'name', value: 'Suresh', provenance: { callId: 'old', saidOrInferred: 'said', atMs: T0 - 1 } }], gists: [] },
      },
      buyer: { legId: 'b1', source: 'simulator', language: 'ta-IN', joinedMs: T0 + 5000, endReason: 'hangup', timings: [timing('b1', 'buyer', T0 + 6000)] },
      ...over,
    },
    ...top,
  }
}

describe('call codes', () => {
  it('gives a short stable code per call id', () => {
    expect(callCode('call-synthetic-1')).toMatch(/^G1-[0-9A-HJKMNP-TV-Z]{4}$/)
    expect(callCode('call-synthetic-1')).toBe(callCode('call-synthetic-1'))
    expect(callCode('call-synthetic-2')).not.toBe(callCode('call-synthetic-1'))
  })
})

describe('filters and sort', () => {
  const calls = [
    summary('a', { startedMs: T0 + 1, durationMs: 10_000, outcome: 'no_terms', languages: { farmer: 'bn-IN' } }),
    summary('b', { startedMs: T0 + 3, durationMs: 5_000 }),
    summary('c', { startedMs: T0 + 2, durationMs: 90_000, outcome: 'no_deal', languages: { buyer: 'ta-IN' } }),
  ]
  it('filters by language and outcome and sorts', () => {
    expect(filterCalls(calls, NO_FILTER).map((c) => c.callId)).toEqual(['b', 'c', 'a'])
    expect(filterCalls(calls, { ...NO_FILTER, sort: 'oldest' }).map((c) => c.callId)).toEqual(['a', 'c', 'b'])
    expect(filterCalls(calls, { ...NO_FILTER, sort: 'longest' }).map((c) => c.callId)).toEqual(['c', 'a', 'b'])
    expect(filterCalls(calls, { ...NO_FILTER, language: 'ta-IN' }).map((c) => c.callId)).toEqual(['b', 'c'])
    expect(filterCalls(calls, { ...NO_FILTER, outcome: 'no_terms' }).map((c) => c.callId)).toEqual(['a'])
    expect(languagesOf(calls)).toEqual(['bn-IN', 'hi-IN', 'ta-IN'])
  })
})

describe('the Calls list', () => {
  it('shows the honest empty state', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain('No calls stored yet')
    expect(html).toContain('A call is stored only when a party says yes to remembering it.')
  })

  it('lists each call with its code, full id on hover, outcome, consent and terms', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[summary('call-synthetic-1', { consentBuyer: 'NO', languages: { farmer: 'hi-IN' } })]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain(callCode('call-synthetic-1'))
    expect(html).toContain('title="call-synthetic-1"')
    expect(html).toContain('href="#/analytics/calls?id=call-synthetic-1"')
    expect(html).toContain('Terms agreed')
    expect(html).toContain('Farmer: Yes')
    expect(html).toContain('Buyer: No')
    expect(html).toContain('onion')
  })

  it('says the figures cover stored calls only', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={{ storedCalls: 2, bothYesDeals: 1, averageDurationMs: 90_000, languagePairs: { 'hi-IN ⇄ ta-IN': 1 }, medianMs: { reply: 1200 }, toolCounts: { get_mandi_price: 1 } }} />)
    expect(html).toContain('Stored calls')
    expect(html).toContain('Figures cover stored calls only')
    expect(html).not.toMatch(/farmers helped|impact/i)
  })
})

describe('a stored call', () => {
  it('becomes a call view with no conversation', () => {
    const view = toCallView(record())
    expect(view.items).toEqual([])
    expect(view.translations).toEqual({})
    expect(view.legs.map((l) => l.role)).toEqual(['farmer', 'buyer'])
    expect(view.timings).toHaveLength(2)
    expect(view.tools[1].result).toBe('Not stored')
    expect(view.memory?.callerKey).toBe('')
  })

  it('shows the cards but never subtitles or advice text, and explains what is missing', () => {
    const html = renderToStaticMarkup(<CallDetail record={record({ buyer: null, notStored: { buyer: 'said_no' } }, { consentBuyer: 'NO' })} onDelete={() => {}} />)
    expect(html).toContain('Deal')
    expect(html).toContain('Suresh')
    expect(html).toContain('get_mandi_price')
    expect(html).toContain('Not stored: the buyer said no to remembering this call.')
    expect(html).toContain('Only the kind and time are stored, never the words.')
    expect(html).toContain('Delete record')
    expect(html).not.toContain('Live Subtitles')
  })

  it('explains a farmer side that was not stored', () => {
    const html = renderToStaticMarkup(<CallDetail record={record({ farmer: null, notStored: { farmer: 'withheld' } }, { consentFarmer: 'NONE' })} />)
    expect(html).toContain('caller ID was withheld')
    expect(html).not.toContain('Suresh')
    expect(html).not.toContain('Delete record')
  })
})

describe('Analytics tabs', () => {
  it('opens Live by default and Calls at #/analytics/calls', () => {
    expect(tabOf(null)).toBe('live')
    expect(tabOf('calls')).toBe('calls')
  })
})
