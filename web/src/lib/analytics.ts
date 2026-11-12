import type { CallView, LegView, NotStoredReason, TimelineItem, ToolView, TurnTiming } from './callState'
import { dealPrice, languageLabel, offset, rupees } from './format'
import { comparePrice, dealValue, parseReference } from './pricing'
import type { CallGist, DealState, DealUpdated, LegRole, LegSource, MemoryLoaded, ProfileFact, WhisperKind } from '../types/console'

export type { NotStoredReason } from './callState'

/** A party's answer to "may G-One remember this call?". */
export type Consent = 'YES' | 'NO' | 'NONE'

/**
 * "agreed": both said yes to the terms; "no_deal": terms heard, not agreed; "info_only": price, weather or buyer
 * lookups with no buyer on the call; "no_terms": no terms heard.
 */
export type Outcome = 'agreed' | 'no_deal' | 'info_only' | 'no_terms'

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
  /** Version 2: when the deal first reached each state. */
  stateTimes?: Partial<Record<DealState, number>>
  /** Version 2: the terms were read back to both sides. */
  readback?: boolean
  farmerYesMs?: number
  buyerYesMs?: number
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
  /** The connected directory buyer (synthetic), kept with the buyer's yes. Absent on older backends. */
  buyerName?: string | null
  /** Private cautions and tools used, counted from the farmer's side (farmer's yes only). */
  cautions?: number
  tools?: number
}

export interface LanguageSwitch {
  from: string
  to: string
  reason: string
  atMs: number
}

/** Per-stage medians in milliseconds; a stage nobody measured is absent. */
export interface StoredLatency {
  turns: number
  transcript?: number
  translation?: number
  reply?: number
}

export interface StoredSide {
  legId: string | null
  source: LegSource | null
  language: string | null
  joinedMs: number
  endReason: string | null
  timings: TurnTiming[]
  /** Version 2 fields: absent on records stored before them. */
  firstLanguage?: string | null
  endedMs?: number
  languageChanges?: LanguageSwitch[]
  latency?: StoredLatency
}

export interface StoredFarmer extends StoredSide {
  tools: { tool: string; ok: boolean; durationMs: number; atMs: number; result?: string }[]
  advice: { kind: WhisperKind; atMs: number }[]
  /** Null when the caller said they were not the remembered farmer. */
  memory: { returning: boolean; facts: ProfileFact[]; gists: CallGist[] } | null
  identity?: 'confirmed' | 'denied' | 'unchecked'
}

export interface StoredBuyer extends StoredSide {
  match?: { buyerId: string | null; name: string | null }
}

/** GET /api/analytics/calls/{id}. */
export interface CallRecordView extends Omit<CallSummary, 'deal' | 'buyerName' | 'cautions' | 'tools'> {
  createdAt: number
  /** 1 for records stored before the version 2 details; missing on older backends. */
  schemaVersion?: number
  data: {
    deal: StoredDeal | null
    /** Version 2: the newest mandi price reference, kept with any stored call. */
    price?: { reference: string; atMs: number } | null
    notStored: Partial<Record<LegRole | 'memory', NotStoredReason>>
    farmer: StoredFarmer | null
    buyer: StoredBuyer | null
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
  agreed: 'Deal agreed',
  no_deal: 'Not agreed',
  info_only: 'Information only',
  no_terms: 'No deal discussed',
}

/** The label for any outcome a backend sends, including one this page does not know yet. */
export function outcomeLabel(outcome: string): string {
  return OUTCOME_LABEL[outcome as Outcome] ?? outcome.replace(/_/g, ' ')
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
    case 'not_this_farmer':
      return 'Not attached: the caller said they were not the remembered farmer.'
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
    ended: { atMs: side.endedMs ?? record.endedMs, reason: side.endReason ?? record.endReason },
  }
}

/**
 * The timeline a stored call can show without any words: each measured turn as a block (when it was spoken, from the
 * kept timings) and each private note as a tick (kind and time only). Texts are empty and never rendered.
 */
function wordlessItems(record: CallRecordView, timings: TurnTiming[]): TimelineItem[] {
  const turns: TimelineItem[] = timings.map((t) => ({ kind: 'turn', key: `turn-${t.key}`, legId: t.legId, role: t.role, language: '', text: '', startedMs: t.startedMs, atMs: t.heardMs }))
  const farmer = record.data.farmer
  const notes: TimelineItem[] = (farmer?.advice ?? []).map((a, i) => ({ kind: 'whisper', key: `advice-${i}`, legId: farmer?.legId ?? '', whisperKind: a.kind, language: '', text: '', atMs: a.atMs }))
  return [...turns, ...notes].sort((a, b) => a.atMs - b.atMs)
}

/**
 * The stored record as the live cards expect it, marked `origin: 'stored'` so they show it as a stored call. There is
 * never a conversation: no turns' words, replies, translations or private advice text are stored. Tool results other
 * than price lookups are not stored either.
 */
export function toCallView(record: CallRecordView, options: { receipt?: boolean } = {}): CallView {
  const { farmer, buyer, deal } = record.data
  const legs: LegView[] = []
  if (farmer) legs.push(leg('farmer', farmer, record))
  if (buyer) legs.push(leg('buyer', buyer, record))
  const tools: ToolView[] = (farmer?.tools ?? []).map((t, i) => ({
    key: `tool-${i}`,
    tool: t.tool,
    args: null,
    ok: t.ok,
    result: t.result ?? '',
    durationMs: t.durationMs,
    atMs: t.atMs,
  }))
  const timings = [...(farmer?.timings ?? []), ...(buyer?.timings ?? [])].sort((a, b) => a.heardMs - b.heardMs)
  const memory: MemoryLoaded | null = farmer?.memory
    ? { type: 'memory_loaded', callId: record.callId, atMs: record.startedMs, callerKey: '', returning: farmer.memory.returning, facts: farmer.memory.facts, gists: farmer.memory.gists.map((g) => ({ ...g, followUps: g.followUps ?? [] })) }
    : null
  const dealView: DealUpdated | null = deal
    ? { type: 'deal_updated', callId: record.callId, atMs: deal.atMs ?? record.endedMs, state: deal.state, commodity: deal.commodity, variety: deal.variety, market: deal.market, quantity: deal.quantity, price: deal.price, unit: deal.unit, pickup: deal.pickup, farmerYes: deal.farmerYes, buyerYes: deal.buyerYes }
    : null
  return {
    callId: record.callId,
    origin: 'stored',
    startedMs: record.startedMs,
    language: record.languages.farmer ?? record.languages.buyer ?? '',
    legs,
    items: wordlessItems(record, timings),
    languageNotes: [],
    tools,
    translations: {},
    timings,
    memory,
    deal: dealView,
    ended: { atMs: record.endedMs, durationMs: record.durationMs, reason: record.endReason },
    seq: 0,
    recorded: { notStored: record.data.notStored ?? {}, priceReference: record.data.price?.reference ?? null, receipt: options.receipt === true },
  }
}

export type KeyPointTone = 'ok' | 'bad' | 'warn' | 'plain'

export interface KeyPoint {
  id: string
  tone: KeyPointTone
  text: string
}

function cropOf(deal: StoredDeal): string | null {
  return [deal.variety, deal.commodity].filter(Boolean).join(' ') || null
}

function termsLine(deal: StoredDeal): string {
  const price = deal.price !== null ? dealPrice(deal.price, deal.unit) : null
  const value = dealValue(deal.quantity, deal.price, deal.unit)
  const parts = [cropOf(deal), deal.quantity, price ? `at ${price}` : null].filter(Boolean).join(', ')
  return value !== null && deal.unit !== 'total' ? `${parts} (about ${rupees(Math.round(value))} in all)` : parts
}

function switches(role: LegRole, side: StoredSide | null): string | null {
  if (!side) return null
  const changes = (side.languageChanges ?? []).filter((c) => c.from && c.from !== 'auto' && c.from !== c.to)
  const now = side.language ? languageLabel(side.language) : null
  if (!now) return null
  const who = role === 'farmer' ? 'Farmer' : 'Buyer'
  if (changes.length === 0) return `${who} spoke ${now}.`
  return `${who} switched ${changes.map((c) => `${languageLabel(c.from)} to ${languageLabel(c.to)}`).join(', then ')}.`
}

/**
 * The call at a glance, worked out from the stored fields only (no model call, nothing that was not kept): what was
 * agreed, with whom, how the price compared, what was confirmed, what advice was given and what memory was used.
 */
export function keyPoints(record: CallRecordView): KeyPoint[] {
  const { deal, farmer, buyer, notStored, price } = record.data
  const points: KeyPoint[] = []
  const at = (ms: number) => offset(ms, record.startedMs)
  const outcome = record.outcome
  if (deal) {
    const line = termsLine(deal)
    points.push(outcome === 'agreed'
      ? { id: 'outcome', tone: 'ok', text: `Deal agreed: ${line}.` }
      : { id: 'outcome', tone: 'warn', text: `Terms discussed but not agreed${line ? `: ${line}` : ''}.` })
  } else {
    points.push({ id: 'outcome', tone: 'plain', text: outcome === 'info_only' ? 'Information only: the farmer asked for prices, weather or buyers; no buyer joined.' : 'No deal was discussed on this call.' })
  }
  if (buyer?.match?.name) points.push({ id: 'buyer', tone: 'plain', text: `Buyer connected: ${buyer.match.name} (from the buyer directory).` })
  if (deal) {
    const reference = price?.reference ? parseReference(price.reference) : (farmer?.tools ?? []).filter((t) => t.tool === 'get_mandi_price' && t.ok && t.result).map((t) => parseReference(t.result ?? '')).filter((r) => r !== null).pop() ?? null
    const comparison = comparePrice(deal.price, deal.unit, reference)
    if (comparison && reference) {
      const pct = Math.abs(comparison.deltaPct)
      const where = pct < 0.5 ? 'about the same as' : `${pct.toFixed(1)}% ${comparison.deltaPct > 0 ? 'above' : 'below'}`
      points.push({ id: 'price', tone: pct > 25 ? 'warn' : 'plain', text: `Price is ${where} the dated mandi reference of ${rupees(comparison.referencePerQuintal)} per quintal${reference.date ? ` (${reference.date})` : ''}.` })
    }
    if (deal.pickup) points.push({ id: 'pickup', tone: 'plain', text: `Pickup and payment: ${deal.pickup}.` })
    const yes = [
      deal.farmerYes ? `farmer said yes${deal.farmerYesMs ? ` at ${at(deal.farmerYesMs)}` : ''}` : 'farmer did not say yes',
      deal.buyerYes ? `buyer said yes${deal.buyerYesMs ? ` at ${at(deal.buyerYesMs)}` : ''}` : 'buyer did not say yes',
    ].join(', ')
    const confirm = `${deal.readback ? 'Terms read back to both; ' : ''}${yes}.`
    points.push({ id: 'confirm', tone: deal.farmerYes && deal.buyerYes ? 'ok' : 'plain', text: confirm.charAt(0).toUpperCase() + confirm.slice(1) })
  }
  const cautions = (farmer?.advice ?? []).filter((a) => a.kind === 'caution')
  if (cautions.length) points.push({ id: 'caution', tone: 'bad', text: `${cautions.length === 1 ? 'One caution' : `${cautions.length} cautions`} given privately to the farmer (${cautions.map((c) => at(c.atMs)).join(', ')}).` })
  const otherAdvice = (farmer?.advice ?? []).length - cautions.length
  if (otherAdvice > 0) points.push({ id: 'advice', tone: 'plain', text: `${otherAdvice === 1 ? 'One private note' : `${otherAdvice} private notes`} (price reference or tip) given to the farmer.` })
  for (const [role, side] of [['farmer', farmer], ['buyer', buyer]] as const) {
    const line = switches(role, side)
    if (line) points.push({ id: `lang-${role}`, tone: 'plain', text: line })
  }
  if (farmer?.identity === 'denied' || notStored.memory === 'not_this_farmer') {
    points.push({ id: 'memory', tone: 'warn', text: 'The caller said they were not the remembered farmer, so no memory is attached.' })
  } else if (farmer?.memory) {
    const name = farmer.memory.facts.find((f) => f.field === 'name')?.value
    points.push({ id: 'memory', tone: 'plain', text: farmer.memory.returning ? `Returning farmer${name ? ` (${name})` : ''}${farmer.identity === 'confirmed' ? ', name confirmed on the call' : ''}.` : 'First call from this farmer: nothing was remembered yet.' })
  }
  const lookups = (farmer?.tools ?? []).filter((t) => t.ok).length
  if (lookups) points.push({ id: 'tools', tone: 'plain', text: `${lookups} assistant ${lookups === 1 ? 'lookup' : 'lookups'} used.` })
  if (notStored.farmer) points.push({ id: 'ns-farmer', tone: 'plain', text: notStoredText('farmer', notStored.farmer) })
  if (notStored.buyer) points.push({ id: 'ns-buyer', tone: 'plain', text: notStoredText('buyer', notStored.buyer) })
  return points
}

export type AnalyticsTab = 'live' | 'calls'

/** "#/analytics" is the live call; "#/analytics/calls" the stored calls, and "?id=" one of them. */
export function tabOf(param: string | null): AnalyticsTab {
  return param === 'calls' ? 'calls' : 'live'
}
