import { callOutcome, type Farmer, type FarmerDeal } from './farmers'
import { englishText } from './farmersScript'
import type { MemoryGist } from './memory'
import { parseRoute } from './route'

/**
 * Where the Farmers tab is: the list, one farmer's overlay (#/farmers/<key>), or a call overlay stacked on it
 * (#/farmers/<key>?call=<callId>). `seed=1` keeps the development seed on.
 */
export interface FarmersLocation {
  key: string | null
  callId: string | null
  seed: boolean
}

export function parseFarmersHash(hash: string): FarmersLocation {
  const route = parseRoute(hash)
  if (route.view !== 'farmers') return { key: null, callId: null, seed: false }
  return { key: route.param, callId: route.param ? route.query.get('call') || null : null, seed: route.query.get('seed') === '1' }
}

export function farmersHash({ key, callId, seed }: FarmersLocation): string {
  const query = new URLSearchParams()
  if (seed) query.set('seed', '1')
  if (key && callId) query.set('call', callId)
  const search = query.toString()
  return `#/farmers${key ? `/${encodeURIComponent(key)}` : ''}${search ? `?${search}` : ''}`
}

export type OverlayLayer = { kind: 'farmer'; farmer: Farmer } | { kind: 'call'; farmer: Farmer; gist: MemoryGist }

/**
 * The overlays open at a location, bottom first. A call opens only when it is one of this farmer's calls; a call id
 * from another farmer (or one that no longer exists) is ignored and only the farmer opens.
 */
export function overlayStack(farmers: readonly Farmer[] | null, at: FarmersLocation): OverlayLayer[] {
  const farmer = at.key ? farmers?.find((f) => f.callerKey === at.key) : undefined
  if (!farmer) return []
  const gist = at.callId ? farmer.gists.find((g) => g.callId === at.callId) : undefined
  return gist ? [{ kind: 'farmer', farmer }, { kind: 'call', farmer, gist }] : [{ kind: 'farmer', farmer }]
}

/** The location as shown: a call id the stack ignored (another farmer's, or unknown) is dropped. */
export function shownLocation(at: FarmersLocation, stack: readonly OverlayLayer[]): FarmersLocation {
  return stack.some((l) => l.kind === 'call') ? at : { ...at, callId: null }
}

/** Esc, ✕ or a backdrop click closes the top overlay only: a call goes back to its farmer, a farmer to the list. */
export function closeTop(at: FarmersLocation): FarmersLocation {
  return at.callId ? { ...at, callId: null } : { ...at, key: null, callId: null }
}

const attr = (value: string) => value.replace(/["\\]/g, '\\$&')

/** The element that gets focus back when the top overlay closes: the call row it came from, or the farmer row. */
export function focusReturnSelector(at: FarmersLocation): string | null {
  if (at.key && at.callId) return `[data-call-row="${attr(at.callId)}"]`
  if (at.key) return `[data-farmer-row="${attr(at.key)}"]`
  return null
}

export interface CallRow {
  callId: string
  startedMs: number
  /** Null when the call has no recorded end. */
  lengthMs: number | null
  language: string
  outcome: { label: string; tone: 'ok' | 'warn' | 'plain' }
  deal: FarmerDeal | null
  /** "10 quintal red onion", or null without a deal. */
  dealLine: string | null
  followUps: string[]
  /** G-One's short note in English letters, not a transcript. */
  note: string | null
}

/** One farmer's calls, newest first, with only the basics. `deals` is undefined while their deals are being looked up. */
export function callRows(farmer: Farmer, deals: readonly FarmerDeal[] | undefined): CallRow[] {
  return [...farmer.gists]
    .sort((a, b) => b.startedMs - a.startedMs)
    .map((g) => {
      const deal = deals?.find((d) => d.callId === g.callId || d.id === g.callId) ?? null
      return {
        callId: g.callId,
        startedMs: g.startedMs,
        lengthMs: g.endedMs > g.startedMs ? g.endedMs - g.startedMs : null,
        // A stored call summary has no language of its own; the farmer's calls are in this one.
        language: farmer.language,
        outcome: deals === undefined ? { label: 'Checking deal…', tone: 'plain' } : callOutcome(g.callId, [...deals]),
        deal,
        dealLine: deal ? [deal.quantity, englishText(deal.commodity)].filter(Boolean).join(' ') || null : null,
        followUps: g.followUps.map((f) => englishText(f.trim())).filter((f): f is string => Boolean(f)),
        note: englishText(g.summary),
      }
    })
}

const WHEN = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })

/** "13 Nov, 10:42 am". */
export function callWhen(ms: number): string {
  return WHEN.format(new Date(ms))
}
