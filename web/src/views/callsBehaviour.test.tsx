import { afterEach, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import CallHeader from '../live/CallHeader'
import { keyPoints, NO_FILTER, type CallRecordView, type CallSummary } from '../lib/analytics'
import { api, ApiError } from '../lib/api'
import { feedReducer, initialFeed, type FeedState } from '../lib/callState'
import { CallDetail, CallsTable, KpiStrip } from './CallsView'

// Behaviour checks written against the public API that already existed before the every-call work (3f9659b), so
// they can run unchanged against that revision and show what changed. Synthetic records only.
const T0 = 1_794_132_000_000

function noYesRow(): CallSummary {
  return {
    callId: 'call-synthetic-base-no', startedMs: T0, endedMs: T0 + 40_000, durationMs: 40_000, endReason: 'hangup',
    languages: { farmer: 'hi-IN', buyer: 'ta-IN' }, outcome: 'no_deal', consentFarmer: 'NO', consentBuyer: 'NONE',
    deal: { state: 'confirming', commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 2100, unit: 'quintal', pickup: null, farmerYes: true, buyerYes: false },
  }
}

function noYesRecord(): CallRecordView {
  const { deal, ...rest } = noYesRow()
  return {
    ...rest, createdAt: T0 + 41_000, schemaVersion: 4,
    data: {
      deal, notStored: { farmer: 'said_no', buyer: 'no_answer' }, keyed: false, termsOnly: true,
      farmer: { legId: 'f1', source: 'twilio', language: 'hi-IN', joinedMs: T0, endReason: 'hangup', latency: { turns: 4, transcript: 420, transcriptCount: 4, reply: 900, replyCount: 4 }, tools: [{ tool: 'get_mandi_price', ok: true, durationMs: 130, atMs: T0 + 2000 }], advice: [], memory: null },
      buyer: { legId: 'b1', source: 'simulator', language: 'ta-IN', joinedMs: T0 + 5000, endReason: 'hangup' },
    },
  } as CallRecordView
}

describe('behaviour that changed with every call being stored', () => {
  const realFetch = globalThis.fetch
  afterEach(() => { globalThis.fetch = realFetch })

  it('a list row nobody said yes to says so', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[noYesRow()]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain('Conversation not kept: no one said yes')
    expect(html).toContain('Anonymous')
  })

  it('the figures no longer promise that a call is stored only with a yes', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={{ storedCalls: 2, bothYesDeals: 1, averageDurationMs: 90_000, languagePairs: {}, medianMs: { reply: 1200 }, toolCounts: {} }} />)
    expect(html).not.toContain('A call is stored only when a party says yes')
  })

  it('the empty list no longer says calls without a yes leave nothing behind', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).not.toContain('leave nothing behind')
  })

  it('a stored call without a yes shows its stored medians and the terms-only label', () => {
    const html = renderToStaticMarkup(<CallDetail record={noYesRecord()} />)
    expect(html).toContain('420 ms')
    expect(html).toContain('Kept without consent: terms only')
  })

  it('a stored record without notStored opens', () => {
    const sparse = { ...noYesRecord(), data: { deal: null, farmer: null, buyer: null } } as unknown as CallRecordView
    expect(() => keyPoints(sparse)).not.toThrow()
  })

  it('a 503 from the backend is told apart from other server errors', async () => {
    globalThis.fetch = (async () => new Response(JSON.stringify({ error: 'analytics_privacy_cleanup_in_progress' }), { status: 503, headers: { 'content-type': 'application/json' } })) as unknown as typeof fetch
    const error = await api('/api/analytics/calls').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).failure as string).toBe('unavailable')
  })

  it('the live header says whose yes the call is kept with, once both answers are in', () => {
    let state: FeedState = initialFeed
    state = feedReducer(state, { type: 'event', event: { type: 'call_started', callId: 'c', atMs: T0, language: 'hi-IN' } })
    state = feedReducer(state, { type: 'event', event: { type: 'consent_recorded', callId: 'c', atMs: T0 + 1, role: 'farmer', consent: 'YES' } })
    state = feedReducer(state, { type: 'event', event: { type: 'consent_recorded', callId: 'c', atMs: T0 + 1, role: 'buyer', consent: 'NO' } })
    const html = renderToStaticMarkup(<CallHeader call={state.calls.c} calls={[state.calls.c]} onPick={() => {}} />)
    expect(html).toContain('Kept with a yes: Farmer')
  })
})
