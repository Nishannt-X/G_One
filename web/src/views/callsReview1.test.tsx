import { afterEach, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { isAnonymous, keptItems, NO_FILTER, RETRY_MS, watchCalls, type AnalyticsSummary, type CallRecordView, type CallsLoad, type CallSummary } from '../lib/analytics'
import { forgetCaller, forgetNotice } from '../lib/memory'
import { CallDetail, CallsTable, KpiStrip } from './CallsView'

// Synthetic records only: invented ids, names and numbers.
const T0 = 1_794_132_000_000

const row = (over: Partial<CallSummary>): CallSummary => ({
  callId: 'call-synthetic-row', startedMs: T0, endedMs: T0 + 9000, durationMs: 9000, endReason: 'hangup', languages: { farmer: 'hi-IN' },
  outcome: 'no_terms', consentFarmer: 'YES', consentBuyer: 'NONE', deal: null, consentScope: 'farmer', termsOnly: false, schemaVersion: 4, ...over,
})
const listHtml = (calls: CallSummary[]) => renderToStaticMarkup(<CallsTable calls={calls} filter={NO_FILTER} onFilter={() => {}} />)

describe('the anonymous chip in the list follows the stored caller key', () => {
  it('marks a farmer yes without a stored key (withheld or forgotten) as anonymous', () => {
    expect(listHtml([row({ keyed: false })])).toContain('Anonymous')
    expect(isAnonymous(row({ keyed: false }))).toBe(true)
  })

  it('does not mark a farmer yes with a stored key', () => {
    expect(listHtml([row({ keyed: true })])).not.toContain('Anonymous')
  })

  it('makes no claim for a farmer yes from an older server that sends no keyed flag', () => {
    const old = row({})
    delete old.keyed
    expect(isAnonymous(old)).toBe(false)
    expect(listHtml([old])).not.toContain('Anonymous')
    // Without the farmer's yes no key is ever kept, so that row is anonymous on any server.
    expect(listHtml([row({ consentFarmer: 'NO', consentScope: 'none', termsOnly: true })])).toContain('Anonymous')
  })
})

describe('the copy says only what the records hold', () => {
  const summary: AnalyticsSummary = { storedCalls: 3, bothYesDeals: 1, averageDurationMs: 36_000, languagePairs: {}, medianMs: { reply: 1200 }, toolCounts: {}, callsWithYes: 1, callsWithoutYes: 2, unsavedCalls: 0, approximate: true }

  it('qualifies what every call keeps, and what the figures cover', () => {
    const html = renderToStaticMarkup(<KpiStrip summary={summary} />)
    expect(html).toContain('Figures cover the calls stored now.')
    expect(html).toContain('up to the newest 500')
    expect(html).toContain('held back by the privacy cleanup are not counted')
    expect(html).toContain('When available, a call keeps')
    expect(html).not.toContain('are kept for every call')
    expect(html).not.toContain('Figures cover every finished call')
  })

  it('says unsaved calls are not counted yet', () => {
    expect(renderToStaticMarkup(<KpiStrip summary={{ ...summary, unsavedCalls: 1 }} />)).toContain('1 finished call is waiting to be saved and is not counted yet.')
  })

  const sparseNoYes = (): CallRecordView => ({
    callId: 'call-synthetic-sparse-no', startedMs: T0, endedMs: T0 + 5000, durationMs: 5000, endReason: 'hangup', languages: { farmer: 'hi-IN' },
    outcome: 'no_terms', consentFarmer: 'NONE', consentBuyer: 'NONE', createdAt: T0, schemaVersion: 4,
    data: { consentScope: 'none', termsOnly: true, keyed: false, deal: null, notStored: { farmer: 'no_answer' }, buyer: null,
      farmer: { legId: 'f1', source: 'simulator', language: 'hi-IN', joinedMs: T0, endReason: 'hangup', tools: [], advice: [], memory: null } },
  })

  it('lists only the parts a no-yes record actually holds', () => {
    const record = sparseNoYes()
    expect(keptItems(record)).toEqual(['call times', 'consent answers', 'who joined', 'languages'])
    const html = renderToStaticMarkup(<CallDetail record={record} />)
    expect(html).toContain('Conversation not kept: no one said yes.')
    expect(html).toContain('Kept in this record: call times, consent answers, who joined and languages.')
    expect(html).not.toContain('tool names')
    expect(html).not.toContain('deal terms')
    expect(html).not.toContain('latency medians')
  })

  it('names a minimal record as minimal, with or without a yes', () => {
    const base = sparseNoYes()
    for (const consentFarmer of ['YES', 'NO'] as const) {
      const minimal: CallRecordView = { ...base, consentFarmer, languages: {}, data: { consentScope: consentFarmer === 'YES' ? 'farmer' : 'none', termsOnly: true, keyed: false, minimal: true, deal: null, notStored: {}, farmer: null, buyer: null } }
      expect(keptItems(minimal)).toEqual(['call times', 'consent answers'])
      const html = renderToStaticMarkup(<CallDetail record={minimal} />)
      expect(html).toContain('This record is minimal: only times and consent answers were saved.')
      expect(html).not.toContain('Kept in this record')
    }
  })
})

/** Manual timers, so a test decides when a retry fires. */
function fakeTimers() {
  const timers = new Map<number, () => void>()
  let next = 1
  return {
    timers,
    setTimer: (fn: () => void, ms: number) => { expect(ms).toBe(RETRY_MS); const id = next++; timers.set(id, fn); return id as unknown as ReturnType<typeof setTimeout> },
    clearTimer: (id: ReturnType<typeof setTimeout>) => { timers.delete(id as unknown as number) },
    fire() { const [id, fn] = [...timers.entries()][0]; timers.delete(id); fn() },
  }
}
const flush = () => new Promise((r) => setTimeout(r, 0))
const unavailable = (code: string) => new Response(JSON.stringify({ error: code }), { status: 503, headers: { 'content-type': 'application/json' } })
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })

describe('the Calls tab while the privacy cleanup runs', () => {
  const realFetch = globalThis.fetch
  afterEach(() => { globalThis.fetch = realFetch })

  it('shows the notice on a 503, retries on a timer and recovers', async () => {
    let cleanupDone = false
    let requests = 0
    globalThis.fetch = (async (url: string) => {
      requests++
      if (!cleanupDone) return unavailable('analytics_privacy_cleanup_in_progress')
      return url.includes('summary') ? json({ storedCalls: 1 }) : json({ calls: [row({})] })
    }) as unknown as typeof fetch
    const timers = fakeTimers()
    const loads: CallsLoad[] = []
    const stop = watchCalls(null, (l) => loads.push(l), timers)
    await flush()
    expect(loads.at(-1)).toMatchObject({ failure: 'unavailable', failureCode: 'analytics_privacy_cleanup_in_progress' })
    expect(timers.timers.size).toBe(1)

    timers.fire()
    await flush()
    expect(loads.at(-1)?.failure).toBe('unavailable')
    expect(timers.timers.size).toBe(1)

    cleanupDone = true
    timers.fire()
    await flush()
    expect(loads.at(-1)).toMatchObject({ failure: null, calls: [{ callId: 'call-synthetic-row' }], summary: { storedCalls: 1 } })
    expect(timers.timers.size).toBe(0)
    expect(requests).toBe(6)
    stop()
  })

  it('waits for the viewer when the cleanup failed', async () => {
    globalThis.fetch = (async () => unavailable('analytics_privacy_cleanup_failed')) as unknown as typeof fetch
    const timers = fakeTimers()
    const loads: CallsLoad[] = []
    const stop = watchCalls(null, (l) => loads.push(l), timers)
    await flush()
    expect(loads.at(-1)).toMatchObject({ failure: 'unavailable', failureCode: 'analytics_privacy_cleanup_failed' })
    expect(timers.timers.size).toBe(0)
    stop()
  })

  it('clears its timer and sets no state after unmount', async () => {
    globalThis.fetch = (async () => unavailable('analytics_privacy_cleanup_in_progress')) as unknown as typeof fetch
    const timers = fakeTimers()
    const loads: CallsLoad[] = []
    const stop = watchCalls(null, (l) => loads.push(l), timers)
    await flush()
    expect(timers.timers.size).toBe(1)
    stop()
    expect(timers.timers.size).toBe(0)

    // A request still in flight when the view unmounts sets nothing either.
    let release: (r: Response) => void = () => {}
    globalThis.fetch = (() => new Promise<Response>((r) => { release = r })) as unknown as typeof fetch
    const late: CallsLoad[] = []
    const stopLate = watchCalls('call-synthetic-row', (l) => late.push(l), timers)
    stopLate()
    release(json({ callId: 'call-synthetic-row' }))
    await flush()
    expect(late).toEqual([])
    expect(timers.timers.size).toBe(0)
  })
})

describe('forget: accepted and completing', () => {
  const realFetch = globalThis.fetch
  afterEach(() => { globalThis.fetch = realFetch })

  it('reads a 202 accepted_completing through the real request path', async () => {
    const seen: { url: string; method?: string }[] = []
    globalThis.fetch = (async (url: string, init?: RequestInit) => {
      seen.push({ url, method: init?.method })
      return new Response(JSON.stringify({ status: 'accepted_completing' }), { status: 202, headers: { 'content-type': 'application/json' } })
    }) as unknown as typeof fetch
    expect(await forgetCaller('a'.repeat(64))).toBe('completing')
    expect(seen).toEqual([{ url: `/api/memory/${'a'.repeat(64)}`, method: 'DELETE' }])
    const text = forgetNotice('Ramesh', 'completing')
    expect(text).toContain('Forget accepted for Ramesh, completing')
    expect(text).not.toContain('has been forgotten')
  })

  it('reads a 204 as done', async () => {
    globalThis.fetch = (async () => new Response(null, { status: 204 })) as unknown as typeof fetch
    expect(await forgetCaller('b'.repeat(64))).toBe('done')
    expect(forgetNotice('Ramesh', 'done')).toBe('Ramesh has been forgotten.')
  })

  it('fails on a 503, so the view keeps the memory and says it did not work', async () => {
    globalThis.fetch = (async () => unavailable('forget_failed')) as unknown as typeof fetch
    expect(await forgetCaller('c'.repeat(64)).catch((e: unknown) => (e as Error).message)).toBe('unavailable')
  })
})
