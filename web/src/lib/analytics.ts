import type { CallView, LegView, ToolView, TurnTiming } from './callState'
import type { CallGist, DealState, DealUpdated, LegRole, LegSource, MemoryLoaded, ProfileFact, WhisperKind } from '../types/console'

/** A party's answer to "may G-One remember this call?". */
export type Consent = 'YES' | 'NO' | 'NONE'

/** "agreed": both said yes to the terms; "no_deal": terms heard, not agreed; "no_terms": no terms heard. */
export type Outcome = 'agreed' | 'no_deal' | 'no_terms'

export interface StoredDeal {
  state: DealState
  commodity: string | null
  variety: string | null
  market: string | null
  quantity: string | null
  price: number | null
  unit: string | null
  pickup: string | null
  farmerYes: boolean
  buyerYes: boolean
  atMs?: number
}

/** One row of GET /api/analytics/calls. Languages are present only for parties that said yes. */
export interface CallSummary {
  callId: string
  startedMs: number
  endedMs: number
  durationMs: number
  endReason: string
  languages: Partial<Record<LegRole, string>>
  outcome: Outcome
  consentFarmer: Consent
  consentBuyer: Consent
  deal: StoredDeal | null
}

interface StoredSide {
  legId: string | null
  source: LegSource | null
  language: string | null
  joinedMs: number
  endReason: string | null
  timings: TurnTiming[]
}

interface StoredFarmer extends StoredSide {
  tools: { tool: string; ok: boolean; durationMs: number; atMs: number; result?: string }[]
  advice: { kind: WhisperKind; atMs: number }[]
  memory: { returning: boolean; facts: ProfileFact[]; gists: CallGist[] }
}

/** Why a party's side was not stored. */
export type NotStoredReason = 'withheld' | 'forgotten' | 'said_no' | 'no_answer' | 'no_leg'

/** GET /api/analytics/calls/{id}. */
export interface CallRecordView extends Omit<CallSummary, 'deal'> {
  createdAt: number
  data: {
    deal: StoredDeal | null
    notStored: Partial<Record<LegRole, NotStoredReason>>
    farmer: StoredFarmer | null
    buyer: StoredSide | null
  }
}

export interface AnalyticsSummary {
  storedCalls: number
  bothYesDeals: number
  averageDurationMs: number
  languagePairs: Record<string, number>
  medianMs: Partial<Record<'transcript' | 'translation' | 'reply', number>>
  toolCounts: Record<string, number>
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** A short, stable code for a call id, such as "G1-7F3K", for reading out and scanning a list. */
export function callCode(callId: string): string {
  // FNV-1a, 32 bit: the same id always gives the same code.
  let hash = 0x811c9dc5
  for (let i = 0; i < callId.length; i++) {
    hash ^= callId.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  let code = ''
  for (let i = 0; i < 4; i++) {
    code += CROCKFORD[hash & 31]
    hash >>>= 5
  }
  return `G1-${code}`
}

export type SortKey = 'newest' | 'oldest' | 'longest'

export interface CallFilter {
  language: string
  outcome: Outcome | 'all'
  sort: SortKey
}

export const NO_FILTER: CallFilter = { language: 'all', outcome: 'all', sort: 'newest' }

/** Every language code stored across these calls, sorted. */
export function languagesOf(calls: CallSummary[]): string[] {
  return [...new Set(calls.flatMap((c) => Object.values(c.languages).filter((l): l is string => Boolean(l))))].sort()
}

export function filterCalls(calls: CallSummary[], filter: CallFilter): CallSummary[] {
  const kept = calls.filter(
    (c) =>
      (filter.language === 'all' || Object.values(c.languages).includes(filter.language)) &&
      (filter.outcome === 'all' || c.outcome === filter.outcome),
  )
  const order: Record<SortKey, (a: CallSummary, b: CallSummary) => number> = {
    newest: (a, b) => b.startedMs - a.startedMs,
    oldest: (a, b) => a.startedMs - b.startedMs,
    longest: (a, b) => b.durationMs - a.durationMs,
  }
  return [...kept].sort(order[filter.sort])
}

export const OUTCOME_LABEL: Record<Outcome, string> = {
  agreed: 'Terms agreed',
  no_deal: 'No deal',
  no_terms: 'No terms heard',
}

export const CONSENT_LABEL: Record<Consent, string> = { YES: 'Yes', NO: 'No', NONE: 'No answer' }

const PARTY: Record<LegRole, string> = { farmer: 'farmer', buyer: 'buyer' }

/** A plain sentence for a side that was not stored. */
export function notStoredText(role: LegRole, reason: NotStoredReason | undefined): string {
  switch (reason) {
    case 'withheld':
      return 'Not stored: the farmer’s caller ID was withheld, so nothing about the farmer is kept.'
    case 'forgotten':
      return 'Not stored: the farmer asked G-One to forget them.'
    case 'said_no':
      return `Not stored: the ${PARTY[role]} said no to remembering this call.`
    case 'no_leg':
      return 'No buyer joined this call.'
    default:
      return `Not stored: no consent from the ${PARTY[role]}.`
  }
}

function leg(role: LegRole, side: StoredSide, record: CallRecordView): LegView {
  return {
    legId: side.legId ?? `${record.callId}-${role}`,
    role,
    source: side.source ?? 'simulator',
    language: side.language ?? '',
    joinedMs: side.joinedMs || record.startedMs,
    ended: { atMs: record.endedMs, reason: side.endReason ?? record.endReason },
  }
}

/**
 * The stored record as the live cards expect it. There is never a conversation: no turns, replies, translations or
 * whispers are stored. Tool results other than price lookups are not stored either.
 */
export function toCallView(record: CallRecordView): CallView {
  const { farmer, buyer, deal } = record.data
  const legs: LegView[] = []
  if (farmer) legs.push(leg('farmer', farmer, record))
  if (buyer) legs.push(leg('buyer', buyer, record))
  const tools: ToolView[] = (farmer?.tools ?? []).map((t, i) => ({
    key: `tool-${i}`,
    tool: t.tool,
    args: null,
    ok: t.ok,
    result: t.result ?? 'Not stored',
    durationMs: t.durationMs,
    atMs: t.atMs,
  }))
  const timings = [...(farmer?.timings ?? []), ...(buyer?.timings ?? [])].sort((a, b) => a.heardMs - b.heardMs)
  const memory: MemoryLoaded | null = farmer
    ? { type: 'memory_loaded', callId: record.callId, atMs: record.startedMs, callerKey: '', returning: farmer.memory.returning, facts: farmer.memory.facts, gists: farmer.memory.gists }
    : null
  const dealView: DealUpdated | null = deal ? { type: 'deal_updated', callId: record.callId, atMs: deal.atMs ?? record.endedMs, ...deal } : null
  return {
    callId: record.callId,
    origin: 'feed',
    startedMs: record.startedMs,
    language: record.languages.farmer ?? record.languages.buyer ?? '',
    legs,
    items: [],
    languageNotes: [],
    tools,
    translations: {},
    timings,
    memory,
    deal: dealView,
    ended: { atMs: record.endedMs, durationMs: record.durationMs, reason: record.endReason },
    seq: 0,
  }
}

export type AnalyticsTab = 'live' | 'calls'

/** "#/analytics" is the live call; "#/analytics/calls" the stored calls, and "?id=" one of them. */
export function tabOf(param: string | null): AnalyticsTab {
  return param === 'calls' ? 'calls' : 'live'
}
