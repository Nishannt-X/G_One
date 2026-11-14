import { afterEach, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import CallHeader from '../live/CallHeader'
import { callCode, consentKept, hasYes, isAnonymous, keyPoints, NO_FILTER, toCallView, type CallRecordView, type CallSummary } from '../lib/analytics'
import { api, ApiError } from '../lib/api'
import { feedReducer, initialFeed, type FeedState } from '../lib/callState'
import { rupees } from '../lib/format'
import { forgetResult } from '../lib/memory'
import { CallDetail, CallsTable, KpiStrip, NotReadyNote } from './CallsView'

// Synthetic records only: invented ids, names and numbers.
const T0 = 1_794_132_000_000

const deal = { state: 'agreed' as const, commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 2100, unit: 'quintal', pickup: 'At the farm; payment in cash', farmerYes: true, buyerYes: true }

/** Three finished calls with three different answers to "may G-One remember this call?". */
const consented: CallSummary = {
  callId: 'call-synthetic-yes', startedMs: T0 + 3000, endedMs: T0 + 63_000, durationMs: 60_000, endReason: 'deal_agreed',
  languages: { farmer: 'hi-IN', buyer: 'ta-IN' }, outcome: 'agreed', consentFarmer: 'YES', consentBuyer: 'NO', deal,
  buyerName: 'Sharma Traders', cautions: 0, tools: 1, consentScope: 'farmer', termsOnly: false, schemaVersion: 4,
}
const saidNo: CallSummary = {
  callId: 'call-synthetic-no', startedMs: T0 + 2000, endedMs: T0 + 42_000, durationMs: 40_000, endReason: 'hangup',
  languages: { farmer: 'hi-IN', buyer: 'ta-IN' }, outcome: 'no_deal', consentFarmer: 'NO', consentBuyer: 'NONE',
  deal: { ...deal, state: 'confirming', buyerYes: false, termsOnly: true, label: 'kept without consent: terms only' },
  buyerName: 'Sharma Traders', cautions: 1, tools: 1, consentScope: 'none', termsOnly: true, schemaVersion: 4,
}
const hangUp: CallSummary = {
  callId: 'call-synthetic-hangup', startedMs: T0 + 1000, endedMs: T0 + 9000, durationMs: 8000, endReason: 'hangup',
  languages: { farmer: 'hi-IN' }, outcome: 'no_terms', consentFarmer: 'NONE', consentBuyer: 'NONE', deal: null,
  cautions: 0, tools: 0, consentScope: 'none', termsOnly: true, schemaVersion: 4,
}

/** Each list row's markup, newest first. */
function rows(calls: CallSummary[]): string[] {
  const html = renderToStaticMarkup(<CallsTable calls={calls} filter={NO_FILTER} onFilter={() => {}} />)
  return html.split('<li').slice(1)
}

describe('the Calls list shows every call', () => {
  it('lists three calls with three consent outcomes and the right notes', () => {
    const [yes, no, hung] = rows([hangUp, saidNo, consented])
    expect(rows([hangUp, saidNo, consented])).toHaveLength(3)

    expect(yes).toContain(callCode('call-synthetic-yes'))
    expect(yes).toContain('Farmer: Yes')
    expect(yes).toContain('Buyer: No')
    expect(yes).not.toContain('Conversation not kept')
    expect(yes).not.toContain('Kept without consent')
    expect(yes).not.toContain('Anonymous')

    expect(no).toContain(callCode('call-synthetic-no'))
    expect(no).toContain('Farmer: No')
    expect(no).toContain('Buyer: No answer')
    expect(no).toContain('Conversation not kept: no one said yes')
    expect(no).toContain('Kept without consent: terms only')
    expect(no).toContain('Anonymous')
    expect(no).toContain(`onion · 10 quintal · ${rupees(2100)}/quintal · Lasalgaon`)

    expect(hung).toContain(callCode('call-synthetic-hangup'))
    expect(hung).toContain('Farmer: No answer')
    expect(hung).toContain('Conversation not kept: no one said yes')
    expect(hung).toContain('Anonymous')
    // No deal card on this call, so no terms-only label either.
    expect(hung).not.toContain('Kept without consent')
    expect(hung).toContain('No deal discussed')
  })

  it('reads a row from an older backend, which sends no consent scope', () => {
    const { consentScope, termsOnly, schemaVersion, ...old } = saidNo
    void consentScope; void termsOnly; void schemaVersion
    const [row] = rows([old])
    expect(row).toContain('Conversation not kept: no one said yes')
    expect(hasYes(old)).toBe(false)
    expect(hasYes(consented)).toBe(true)
  })

  it('marks a call anonymous only when no caller key can be kept', () => {
    expect(isAnonymous(consented)).toBe(false)
    expect(isAnonymous(saidNo)).toBe(true)
    expect(isAnonymous(hangUp)).toBe(true)
  })

  it('says a call kept only its times and answers when the record is minimal', () => {
    const [row] = rows([{ ...hangUp, consentFarmer: 'YES', consentScope: 'farmer', termsOnly: true }])
    expect(row).not.toContain('no one said yes')
    expect(row).toContain('Only the times and the answers were kept')
  })

  it('has an honest empty state', () => {
    const html = renderToStaticMarkup(<CallsTable calls={[]} filter={NO_FILTER} onFilter={() => {}} />)
    expect(html).toContain('No finished calls yet')
    expect(html).toContain('Every finished call is listed here')
    expect(html).not.toMatch(/stored only when a party says yes|leave nothing behind/)
  })
})

describe('the figures strip', () => {
  const summary = { storedCalls: 3, bothYesDeals: 1, averageDurationMs: 36_000, languagePairs: { 'hi-IN ⇄ ta-IN': 2, 'hi-IN': 1 }, medianMs: { reply: 1200, transcript: 400 }, toolCounts: { get_mandi_price: 2 }, callsWithYes: 1, callsWithoutYes: 2, unsavedCalls: 0, approximate: true }

  it('counts calls with and without a yes, and labels approximate medians', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={summary} />)
    expect(html).toContain('1 with a yes · 2 without')
    expect(html).toContain('Median reply time (measured, approximate)')
    expect(html).toContain('Speech to text, approximate median')
    expect(html).toContain('Figures cover every finished call.')
    expect(html).not.toContain('stored only when a party says yes')
    expect(html).not.toMatch(/real.?time|instant|verified identity|blocks scams/i)
  })

  it('drops the approximate label for a single call, and reads an older summary', () => {
    const one = renderToStaticMarkup(<KpiStrip summary={{ ...summary, storedCalls: 1, callsWithYes: 1, callsWithoutYes: 0, approximate: false }} />)
    expect(one).toContain('Median reply time (measured)')
    expect(one).not.toContain('approximate')
    const old = renderToStaticMarkup(<KpiStrip summary={{ storedCalls: 2, bothYesDeals: 1, averageDurationMs: 90_000, languagePairs: {}, medianMs: { reply: 1200 }, toolCounts: {} }} />)
    expect(old).not.toContain('with a yes ·')
    expect(old).toContain('Median reply time (measured)')
  })

  it('says when finished calls are still waiting to be saved', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={{ ...summary, unsavedCalls: 2 }} />)
    expect(html).toContain('2 finished calls are waiting to be saved')
  })
})

/** A version 4 record for a call nobody said yes to: operational data and the deal terms only. */
function noYesRecord(): CallRecordView {
  return {
    callId: 'call-synthetic-no', startedMs: T0, endedMs: T0 + 40_000, durationMs: 40_000, endReason: 'hangup',
    languages: { farmer: 'hi-IN', buyer: 'ta-IN' }, outcome: 'no_deal', consentFarmer: 'NO', consentBuyer: 'NONE',
    createdAt: T0 + 41_000, schemaVersion: 4,
    data: {
      consentScope: 'none', termsOnly: true, keyed: false, joined: { farmer: true, buyer: true },
      deal: { ...deal, state: 'confirming', buyerYes: false, readback: true, stateTimes: {}, termsOnly: true, label: 'kept without consent: terms only' },
      price: { tool: 'get_mandi_price', atMs: T0 + 2000, offsetMs: 2000 },
      notStored: { farmer: 'said_no', buyer: 'no_answer' },
      farmer: {
        legId: 'f1', source: 'twilio', language: 'hi-IN', firstLanguage: 'hi-IN', joinedMs: T0, endedMs: T0 + 40_000, endReason: 'hangup',
        languageChanges: [], latency: { turns: 4, transcript: 420, transcriptCount: 4, reply: 900, replyCount: 4 }, identity: 'unchecked',
        tools: [{ tool: 'get_mandi_price', ok: true, durationMs: 130, atMs: T0 + 2000, offsetMs: 2000 }],
        advice: [{ kind: 'caution', atMs: T0 + 9000, offsetMs: 9000 }], memory: null,
      },
      buyer: {
        legId: 'b1', source: 'simulator', language: 'ta-IN', firstLanguage: 'ta-IN', joinedMs: T0 + 5000, endedMs: T0 + 39_000, endReason: 'hangup',
        languageChanges: [], latency: { turns: 3, transcript: 380, transcriptCount: 3, translation: 450, translationCount: 3 },
        match: { buyerId: 'buyer-synthetic-7', name: 'Sharma Traders' },
      },
    },
  }
}

describe('a stored call nobody said yes to', () => {
  it('shows the operational cards, the terms-only deal card and what was not kept', () => {
    const html = renderToStaticMarkup(<CallDetail record={noYesRecord()} onDelete={() => {}} />)
    for (const part of ['Stored call', 'Farmer: No', 'Buyer: No answer', 'Anonymous', 'Deal Card', 'Kept without consent: terms only', 'Turn Latency', 'AI Tools', 'get_mandi_price', 'Private Whisper', 'Caution', 'Sharma Traders', 'Key points']) {
      expect(html).toContain(part)
    }
    expect(html).toContain('Conversation not kept: no one said yes.')
    expect(html).toContain('per-turn times')
    expect(html).toContain(`${rupees(2100)} / quintal`)
    // The stored medians (no per-turn timings without a yes).
    expect(html).toContain('420 ms')
    expect(html).toContain('900 ms')
    expect(html).not.toContain('Live Subtitles')
    expect(html).not.toMatch(/real.?time|instant|verified identity|blocks scams/i)
  })

  it('draws no turn blocks, only the private note ticks', () => {
    const view = toCallView(noYesRecord())
    expect(view.timings).toEqual([])
    expect(view.items.map((i) => i.kind)).toEqual(['whisper'])
    expect(view.items.every((i) => i.text === '')).toBe(true)
    expect(view.tools.map((t) => t.tool)).toEqual(['get_mandi_price'])
    expect(view.recorded?.termsOnly).toBe(true)
    expect(view.recorded?.latency).toMatchObject({ transcript: 420, translation: 450, reply: 900, approximate: true })
  })

  it('keeps the key points to the kept fields', () => {
    const text = keyPoints(noYesRecord()).map((p) => p.text).join('\n')
    expect(text).toContain('Terms discussed but not agreed: onion, 10 quintal')
    expect(text).toContain('One caution given privately to the farmer (+0:09).')
    expect(text).toContain('Buyer connected: Sharma Traders')
  })

  it('opens a minimal record, which keeps only its times and answers', () => {
    const minimal = { ...noYesRecord(), consentFarmer: 'YES', outcome: 'no_terms', endReason: 'other', languages: {}, data: { consentScope: 'farmer', termsOnly: true, keyed: false, minimal: true, deal: null, notStored: {}, farmer: null, buyer: null } } as CallRecordView
    const html = renderToStaticMarkup(<CallDetail record={minimal} />)
    expect(html).toContain('Only the times and the answers were kept')
    expect(html).not.toContain('no one said yes')
  })

  it('opens a record with optional fields and arrays missing', () => {
    const sparse = { callId: 'call-synthetic-sparse', startedMs: T0, endedMs: T0 + 5000, durationMs: 5000, endReason: 'hangup', outcome: 'no_terms', consentFarmer: 'NONE', consentBuyer: 'NONE', createdAt: T0, data: { deal: null, farmer: { legId: 'f1', source: 'twilio', language: 'hi-IN', joinedMs: T0 } } } as unknown as CallRecordView
    expect(() => toCallView(sparse)).not.toThrow()
    expect(() => keyPoints(sparse)).not.toThrow()
    const html = renderToStaticMarkup(<CallDetail record={sparse} />)
    expect(html).toContain('Conversation not kept: no one said yes.')
    expect(html).toContain(callCode('call-synthetic-sparse'))
  })

  it('leaves the consented stored view without the not-kept note or the terms-only label', () => {
    const yes = { ...noYesRecord(), consentFarmer: 'YES' as const, consentBuyer: 'YES' as const }
    yes.data = { ...yes.data, consentScope: 'both', termsOnly: false, keyed: true, notStored: {}, deal: { ...deal } }
    const html = renderToStaticMarkup(<CallDetail record={yes} />)
    expect(html).not.toContain('Conversation not kept')
    expect(html).not.toContain('Kept without consent')
    expect(html).not.toContain('Anonymous')
  })
})

describe('API states', () => {
  const realFetch = globalThis.fetch
  afterEach(() => {
    globalThis.fetch = realFetch
  })

  it('turns a 503 during the privacy cleanup into a friendly notice', async () => {
    globalThis.fetch = (async () => new Response(JSON.stringify({ error: 'analytics_privacy_cleanup_in_progress' }), { status: 503, headers: { 'content-type': 'application/json' } })) as unknown as typeof fetch
    const error = await api('/api/analytics/calls').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).failure).toBe('unavailable')
    expect((error as ApiError).code).toBe('analytics_privacy_cleanup_in_progress')
    const html = renderToStaticMarkup(<NotReadyNote code="analytics_privacy_cleanup_in_progress" onRetry={() => {}} />)
    expect(html).toContain('one-time privacy cleanup')
    expect(html).toContain('Try again')
    const failed = renderToStaticMarkup(<NotReadyNote code="analytics_privacy_cleanup_failed" onRetry={() => {}} />)
    expect(failed).toContain('did not finish')
  })

  it('reads a 202 forget as accepted and completing, and a 204 as done', () => {
    expect(forgetResult({ status: 'accepted_completing' })).toBe('completing')
    expect(forgetResult(null)).toBe('done')
  })
})

describe('the live call says whether it will be kept', () => {
  function feed(...consents: ['farmer' | 'buyer', 'YES' | 'NO' | 'NONE'][]): FeedState {
    let state: FeedState = initialFeed
    state = feedReducer(state, { type: 'event', event: { type: 'call_started', callId: 'c', atMs: T0, language: 'hi-IN' } })
    state = feedReducer(state, { type: 'event', event: { type: 'leg_joined', callId: 'c', atMs: T0, legId: 'f', role: 'farmer', source: 'simulator', language: 'hi-IN' } })
    for (const [role, consent] of consents) {
      state = feedReducer(state, { type: 'event', event: { type: 'consent_recorded', callId: 'c', atMs: T0 + 9000, role, consent } })
    }
    return state
  }

  it('shows nothing before the consent answers are known', () => {
    const call = feed().calls.c
    expect(consentKept(call.consent)).toBeNull()
    const html = renderToStaticMarkup(<CallHeader call={call} calls={[call]} onPick={() => {}} />)
    expect(html).not.toContain('Kept with a yes')
    expect(html).not.toContain('not kept')
  })

  it('names who said yes, or says nothing is kept', () => {
    expect(consentKept(feed(['farmer', 'YES'], ['buyer', 'NO']).calls.c.consent)).toEqual(['farmer'])
    expect(consentKept(feed(['farmer', 'YES'], ['buyer', 'YES']).calls.c.consent)).toEqual(['farmer', 'buyer'])
    expect(consentKept(feed(['farmer', 'NONE'], ['buyer', 'NONE']).calls.c.consent)).toEqual([])

    const kept = feed(['farmer', 'YES'], ['buyer', 'NO']).calls.c
    expect(renderToStaticMarkup(<CallHeader call={kept} calls={[kept]} onPick={() => {}} />)).toContain('Kept with a yes: Farmer')
    const none = feed(['farmer', 'NONE'], ['buyer', 'NONE']).calls.c
    expect(renderToStaticMarkup(<CallHeader call={none} calls={[none]} onPick={() => {}} />)).toContain('Conversation not kept: no one said yes')
  })

  it('never shows the live indicator on a stored call', () => {
    const view = toCallView(noYesRecord())
    const html = renderToStaticMarkup(<CallHeader call={view} calls={[view]} onPick={() => {}} />)
    expect(html).not.toContain('Kept with a yes')
    expect(html).not.toContain('Conversation not kept')
  })
})
