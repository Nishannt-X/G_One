import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { callCode, filterCalls, keyPoints, languagesOf, NO_FILTER, tabOf, toCallView, type CallRecordView, type CallSummary } from '../lib/analytics'
import { rupees } from '../lib/format'
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
    expect(html).toContain('No finished calls yet')
    expect(html).not.toContain('A call is stored only when a party says yes to remembering it.')
  })

  it('lists each call with its code, full id on hover, outcome, consent and terms', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[summary('call-synthetic-1', { consentBuyer: 'NO', languages: { farmer: 'hi-IN' } })]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain(callCode('call-synthetic-1'))
    expect(html).toContain('title="call-synthetic-1"')
    expect(html).toContain('href="#/analytics/calls?id=call-synthetic-1"')
    expect(html).toContain('Deal agreed')
    expect(html).toContain('Farmer: Yes')
    expect(html).toContain('Buyer: No')
    expect(html).toContain('onion')
  })

  it('says the figures cover every finished call', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={{ storedCalls: 2, bothYesDeals: 1, averageDurationMs: 90_000, languagePairs: { 'hi-IN ⇄ ta-IN': 1 }, medianMs: { reply: 1200 }, toolCounts: { get_mandi_price: 1 } }} />)
    expect(html).toContain('Finished calls')
    expect(html).toContain('Figures cover the calls stored now.')
    expect(html).not.toContain('Figures cover stored calls only')
    expect(html).not.toMatch(/farmers helped|impact/i)
  })
})

/** A version 2 record: everything the stored view can show. */
function fullRecord(over: Partial<CallRecordView['data']> = {}): CallRecordView {
  const base = record()
  const farmer = base.data.farmer!
  return {
    ...base,
    endReason: 'deal_agreed',
    schemaVersion: 2,
    data: {
      ...base.data,
      deal: { ...base.data.deal!, variety: 'Red', pickup: 'Friday at the farm, cash on pickup', readback: true, stateTimes: { draft: T0 + 4000, readback: T0 + 5000, agreed: T0 + 7000 }, farmerYesMs: T0 + 6000, buyerYesMs: T0 + 7000 },
      price: { reference: 'Agmarknet listed 2,150 rupees per quintal for onion at Lasalgaon on 2026-11-01.', atMs: T0 + 2000 },
      farmer: {
        ...farmer,
        firstLanguage: 'auto',
        endedMs: T0 + 60_000,
        endReason: 'assistant_hangup',
        languageChanges: [{ from: 'auto', to: 'hi-IN', reason: 'detected', atMs: T0 + 900 }],
        latency: { turns: 1, transcript: 400, translation: 400 },
        identity: 'confirmed',
        memory: { ...farmer.memory!, gists: [{ callId: 'old', startedMs: T0 - 86_400_000, endedMs: T0 - 86_000_000, summary: 'Asked about onion prices.', followUps: ['Call back with a buyer'] }] },
      },
      buyer: { ...base.data.buyer!, firstLanguage: 'ta-IN', endedMs: T0 + 50_000, languageChanges: [{ from: 'ta-IN', to: 'en-IN', reason: 'named', atMs: T0 + 5500 }], match: { buyerId: 'buyer-synthetic-7', name: 'Sharma Traders' } },
      ...over,
    },
  }
}

describe('a stored call', () => {
  it('becomes a stored call view with no words anywhere', () => {
    const view = toCallView(record())
    expect(view.origin).toBe('stored')
    expect(view.translations).toEqual({})
    expect(view.items.every((item) => item.text === '')).toBe(true)
    expect(view.items.map((item) => item.kind)).toEqual(['turn', 'whisper', 'turn'])
    expect(view.legs.map((l) => l.role)).toEqual(['farmer', 'buyer'])
    expect(view.timings).toHaveLength(2)
    expect(view.tools[1].result).toBe('')
    expect(view.memory?.callerKey).toBe('')
    expect(view.recorded?.receipt).toBe(false)
  })

  it('uses the stored leg end times, and the price reference kept with any record', () => {
    const view = toCallView(fullRecord({ farmer: null, notStored: { farmer: 'said_no' } }), { receipt: true })
    expect(view.legs.map((l) => l.ended?.atMs)).toEqual([T0 + 50_000])
    expect(view.recorded?.priceReference).toContain('2,150 rupees per quintal')
    expect(view.recorded?.receipt).toBe(true)
  })

  it('shows every live card except the subtitles, as a stored call', () => {
    const html = renderToStaticMarkup(<CallDetail record={fullRecord()} receipt onDelete={() => {}} />)
    for (const section of ['Stored call', 'Started ', 'Deal Agreed: Both farmer and buyer', 'Private Whisper', 'Deal Card', 'Price Comparison', 'Turn Latency', 'Farmer Memory', 'AI Tools', 'Key points', 'Delete record']) {
      expect(html).toContain(section)
    }
    expect(html).not.toContain('Live Subtitles')
    expect(html).not.toContain('Live Call')
    expect(html).not.toContain('Saving a summary')
    expect(html).not.toContain('Waiting to join')
    // Private advice: kind and time only.
    expect(html).toContain('Only the kind and time of each note were kept, never the words.')
    expect(html).toContain('Caution')
    // Deal terms, price comparison and memory from the record.
    expect(html).toContain('Red onion')
    expect(html).toContain('Friday at the farm, cash on pickup')
    expect(html).toContain('2.3% below')
    expect(html).toContain('Suresh')
    expect(html).toContain('Asked about onion prices.')
    expect(html).toContain('get_mandi_price')
    expect(html).toContain('Sharma Traders')
    expect(html).toContain('Measured')
    expect(html).toContain('receipt.html')
  })

  it('hides the receipt link when the receipt is not on the server', () => {
    const html = renderToStaticMarkup(<CallDetail record={fullRecord()} />)
    expect(html).not.toContain('receipt.html')
  })

  it('works the key points out of the stored fields only', () => {
    const points = keyPoints(fullRecord())
    const text = points.map((p) => p.text).join('\n')
    expect(points[0]).toMatchObject({ id: 'outcome', tone: 'ok' })
    expect(text).toContain('Deal agreed: Red onion, 10 quintal, at ₹')
    expect(text).toContain('Buyer connected: Sharma Traders')
    expect(text).toContain('2.3% below the dated mandi reference')
    expect(text).toContain('Terms read back to both; farmer said yes at +0:06, buyer said yes at +0:07.')
    expect(text).toContain('One caution given privately to the farmer (+0:03).')
    expect(text).toContain('Buyer switched Tamil to English.')
    expect(text).toContain('Returning farmer (Suresh), name confirmed on the call.')
    expect(text).not.toMatch(/real.?time|instant|verified|binding/i)
  })

  it('says when a caller denied being the remembered farmer', () => {
    const base = fullRecord()
    const html = renderToStaticMarkup(<CallDetail record={fullRecord({ farmer: { ...base.data.farmer!, identity: 'denied', memory: null }, notStored: { memory: 'not_this_farmer' } })} />)
    expect(html).toContain('not the remembered farmer')
    expect(html).not.toContain('Suresh')
  })

  it('opens a version 1 record, which has none of the newer fields', () => {
    const html = renderToStaticMarkup(<CallDetail record={record({ buyer: null, notStored: { buyer: 'said_no' } }, { consentBuyer: 'NO' })} onDelete={() => {}} />)
    expect(html).toContain('Stored before leg end times')
    expect(html).toContain('Suresh')
    expect(html).toContain('get_mandi_price')
    expect(html).toContain('Not stored: the buyer said no to remembering this call.')
    expect(html).toContain('Not stored')
    expect(html).not.toContain('Live Subtitles')
  })

  it('explains a farmer side that was not stored', () => {
    const html = renderToStaticMarkup(<CallDetail record={record({ farmer: null, notStored: { farmer: 'withheld' } }, { consentFarmer: 'NONE' })} />)
    expect(html).toContain('caller ID was withheld')
    expect(html).not.toContain('Suresh')
    expect(html).not.toContain('Delete record')
  })
})

describe('a stored call in the list', () => {
  it('shows the outcome, crop and price, buyer and cautions at a glance', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[summary('call-x', { buyerName: 'Sharma Traders', cautions: 2, deal: { ...summary('x').deal!, variety: 'Red' } }), summary('call-y', { outcome: 'info_only', deal: null })]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain('Red onion · 10 quintal')
    expect(html).toContain('Buyer: Sharma Traders · 2 cautions')
    expect(html).toContain('Information only')
    expect(html).toContain('1:00')
    expect(html).toContain('Hindi ⇄ Tamil')
  })
})

describe('deal price text', () => {
  it('keeps the approved per-unit wording in the list and the key points', () => {
    const list = renderToStaticMarkup(<CallsTable calls={[summary('call-x')]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(list).toContain(`onion · 10 quintal · ${rupees(2100)}/quintal · Lasalgaon`)
    const outcome = keyPoints(record()).find((p) => p.id === 'outcome')!
    expect(outcome.text).toBe(`Deal agreed: onion, 10 quintal, at ${rupees(2100)} per quintal (about ${rupees(21000)} in all).`)
  })

  it('shows a total as a total and never multiplies it', () => {
    const totalDeal = { ...summary('t').deal!, quantity: '10 kg', price: 2000, unit: 'total' }
    const list = renderToStaticMarkup(<CallsTable calls={[summary('call-t', { deal: totalDeal })]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(list).toContain(`onion · 10 kg · ${rupees(2000)} total · Lasalgaon`)
    const outcome = keyPoints(record({ deal: totalDeal })).find((p) => p.id === 'outcome')!
    expect(outcome.text).toBe(`Deal agreed: onion, 10 kg, at ${rupees(2000)} total.`)
  })
})

describe('Analytics tabs', () => {
  it('opens Calls by default and Live at #/analytics/live', () => {
    expect(tabOf(null)).toBe('calls')
    expect(tabOf('calls')).toBe('calls')
    expect(tabOf('live')).toBe('live')
  })
})
