import { api, ApiError, type ApiFailure } from './api'
import type { CallView, LegView, NotStoredReason, RecordedLatency, TimelineItem, ToolView, TurnTiming } from './callState'
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

/** Whose answer to the remembering question was yes. Version 3 and later. */
export type ConsentScope = 'both' | 'farmer' | 'buyer' | 'none'

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
  /** Version 3: true when no party said yes, so the card was kept as terms only. */
  termsOnly?: boolean
  /** Version 3: "kept without consent: terms only" on a terms-only card. */
  label?: string
}

/**
 * One row of GET /api/analytics/calls. Every finished call has one (version 3 and later); on older records the
 * languages are present only for parties that said yes.
 */
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
  /** Version 3: whose yes covers the record, and true when only operational data and the deal terms were kept. */
  consentScope?: ConsentScope
  termsOnly?: boolean
  /** True only when the record holds a caller key (never the key itself); absent on older servers. */
  keyed?: boolean
  schemaVersion?: number
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
  /** How many turns each median covers (version 3); absent on older records. */
  transcriptCount?: number
  translationCount?: number
  replyCount?: number
}

export interface StoredSide {
  legId: string | null
  source: LegSource | null
  language: string | null
  joinedMs: number
  endReason: string | null
  /** Per-turn times: kept only with that party's yes (always present on version 1 and 2 records). */
  timings?: TurnTiming[]
  /** Version 2 fields: absent on records stored before them. */
  firstLanguage?: string | null
  endedMs?: number
  languageChanges?: LanguageSwitch[]
  latency?: StoredLatency
}

export interface StoredFarmer extends StoredSide {
  tools?: { tool: string; ok: boolean; durationMs: number; atMs: number; offsetMs?: number; result?: string }[]
  advice?: { kind: WhisperKind; atMs: number; offsetMs?: number }[]
  /** Null without the farmer's yes, and when the caller said they were not the remembered farmer. */
  memory?: { returning: boolean; facts: ProfileFact[]; gists: CallGist[] } | null
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
    /** Version 2: the newest mandi price lookup; the reference sentence itself is absent from version 4 on. */
    price?: { reference?: string; atMs: number; offsetMs?: number; tool?: string } | null
    /** Absent on some older records. */
    notStored?: Partial<Record<LegRole | 'memory', NotStoredReason>>
    farmer: StoredFarmer | null
    buyer: StoredBuyer | null
    /** Version 3 and later: whose yes covers the record. */
    consentScope?: ConsentScope
    /** True when only operational data and the deal terms were kept (nobody said yes, or a minimal record). */
    termsOnly?: boolean
    /** False when no caller key is kept: the call is not linked to any farmer. */
    keyed?: boolean
    /** A record that kept only its times and the answers. */
    minimal?: boolean
    /** Version 3 and later: which legs joined the call. */
    joined?: Partial<Record<LegRole, boolean>>
  }
}

export interface AnalyticsSummary {
  storedCalls: number
  bothYesDeals: number
  averageDurationMs: number
  languagePairs: Record<string, number>
  medianMs: Partial<Record<'transcript' | 'translation' | 'reply', number>>
  toolCounts: Record<string, number>
  /** Version 3 and later: calls at least one party said yes to, and the rest (kept as terms only). */
  callsWithYes?: number
  callsWithoutYes?: number
  /** Finished calls still waiting to be saved; not counted yet. */
  unsavedCalls?: number
  /** True when the medians span more than one call: a count-weighted median of per-call medians. */
  approximate?: boolean
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

type Answers = Pick<CallSummary, 'consentFarmer' | 'consentBuyer'>

/** True when at least one party said yes to remembering the call, read from the recorded answers. */
export function hasYes(call: Answers): boolean {
  return call.consentFarmer === 'YES' || call.consentBuyer === 'YES'
}

/**
 * True when the call is not linked to any farmer: no caller key is stored. Read from the record's or the row's
 * `keyed` flag. Without it (older servers) only a call without the farmer's yes is certainly anonymous, since a key is
 * kept only with that yes; a farmer-yes row then makes no claim (a full record also checks a withheld or forgotten
 * caller).
 */
export function isAnonymous(call: Answers & { keyed?: boolean; data?: CallRecordView['data'] }): boolean {
  const keyed = typeof call.data?.keyed === 'boolean' ? call.data.keyed : call.keyed
  if (typeof keyed === 'boolean') return !keyed
  if (call.consentFarmer !== 'YES') return true
  const why = call.data?.notStored?.farmer
  return why === 'withheld' || why === 'forgotten'
}

export const NOT_KEPT_NOTE = 'Conversation not kept: no one said yes'
export const MINIMAL_NOTE = 'This record is minimal: only times and consent answers were saved'
export const TERMS_ONLY_LABEL = 'Kept without consent: terms only'

/**
 * The short note a list row or a stored call shows when its consent-gated content was not kept, or null. A minimal
 * record says so first; a list row cannot tell a minimal record apart unless someone said yes (it is then terms only).
 */
export function keptNote(call: Answers & { termsOnly?: boolean; data?: CallRecordView['data'] }): string | null {
  if (call.data?.minimal) return MINIMAL_NOTE
  if (!hasYes(call)) return NOT_KEPT_NOTE
  return call.termsOnly ? MINIMAL_NOTE : null
}

/** What a stored record actually holds, in plain words, for the "what was kept" note. Never more than is there. */
export function keptItems(record: CallRecordView): string[] {
  const items = ['call times', 'consent answers']
  if (record.data.minimal) return items
  const { farmer, buyer, deal } = record.data
  const sides = [farmer, buyer].filter((s): s is StoredFarmer | StoredBuyer => Boolean(s))
  if (sides.length) items.push('who joined')
  if (Object.keys(record.languages ?? {}).length || sides.some((s) => s.language)) items.push('languages')
  if (sides.some((s) => s.latency && (s.latency.transcript ?? s.latency.translation ?? s.latency.reply) !== undefined)) items.push('latency medians')
  if (farmer?.tools?.length) items.push('tool names')
  if (farmer?.advice?.length) items.push('private-advice kinds')
  if (deal) items.push('the deal terms')
  return items
}

/** "a, b and c". */
export function plainList(items: string[]): string {
  return items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/** True when a deal card was kept as terms only (no party said yes). */
export function termsOnlyDeal(call: Answers, deal: StoredDeal | null | undefined): boolean {
  return Boolean(deal) && (deal?.termsOnly ?? !hasYes(call))
}

/** Whether a live call will be kept: still waiting on an answer, or final with the parties who said yes. */
export type LiveConsent = { state: 'pending'; yes: LegRole[]; waiting: LegRole[] } | { state: 'final'; kept: LegRole[] }

const ROLES: LegRole[] = ['farmer', 'buyer']

/**
 * The live call's consent status. Final only once both parties' answers are in, or once the call has ended (a missing
 * answer then counts as none, as the backend records it). Pending while one answer is still out. Null before any
 * answer, for a stored call, and for a scripted call that ended without answers (it never asked).
 */
export function liveConsent(call: Pick<CallView, 'consent' | 'ended' | 'origin'>): LiveConsent | null {
  if (call.origin === 'stored') return null
  const answers = call.consent ?? {}
  const known = ROLES.filter((role) => answers[role] !== undefined)
  const ended = Boolean(call.ended) && (known.length > 0 || call.origin === 'feed')
  if (known.length === ROLES.length || ended) return { state: 'final', kept: ROLES.filter((role) => answers[role] === 'YES') }
  if (known.length === 0) return null
  return { state: 'pending', yes: known.filter((role) => answers[role] === 'YES'), waiting: ROLES.filter((role) => answers[role] === undefined) }
}

const PARTY: Record<LegRole, string> = { farmer: 'farmer', buyer: 'buyer' }

/**
 * A plain sentence for a side that was not stored. `sideKept` is true when the side's operational details (times,
 * tools, advice kinds) were kept and only what needs a yes is missing.
 */
export function notStoredText(role: LegRole, reason: NotStoredReason | undefined, sideKept = false): string {
  if (sideKept && reason !== 'no_leg' && reason !== 'not_this_farmer') return keptWithoutYesText(role, reason)
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

function keptWithoutYesText(role: LegRole, reason: NotStoredReason | undefined): string {
  const missing = role === 'farmer' ? 'their per-turn times, name and memory were not kept' : 'their per-turn times were not kept'
  switch (reason) {
    case 'withheld':
      return 'The farmer’s caller ID was withheld: the call is not linked to any farmer, and no name or memory was kept.'
    case 'forgotten':
      return 'The farmer asked G-One to forget them: the call is no longer linked to them.'
    case 'said_no':
      return `The ${PARTY[role]} said no to remembering this call: ${missing}.`
    default:
      return `The ${PARTY[role]} did not say yes to remembering this call: ${missing}.`
  }
}

/**
 * The stored medians of a call without per-turn times, per stage: the count-weighted median of each side's median,
 * worked out like the backend's summary. `approximate` is true when more than one side's median went into a stage.
 */
export function storedLatency(record: CallRecordView): RecordedLatency | null {
  const sides = [record.data.farmer, record.data.buyer].filter((s): s is StoredSide => Boolean(s?.latency))
  const out: RecordedLatency = { turns: 0, approximate: false }
  for (const side of sides) out.turns = Math.max(out.turns, side.latency?.turns ?? 0)
  for (const stage of ['transcript', 'translation', 'reply'] as const) {
    const values = sides.flatMap((s) => {
      const l = s.latency!
      const v = l[stage]
      return typeof v === 'number' ? [[v, Math.max(1, l[`${stage}Count`] ?? l.turns ?? 1)] as const] : []
    })
    if (values.length === 0) continue
    if (values.length > 1) out.approximate = true
    out[stage] = weightedMedian(values)
  }
  return out.transcript === undefined && out.translation === undefined && out.reply === undefined ? null : out
}

function weightedMedian(values: readonly (readonly [number, number])[]): number {
  const sorted = [...values].sort((a, b) => a[0] - b[0])
  const n = sorted.reduce((sum, [, count]) => sum + count, 0)
  const at = (index: number) => {
    let seen = 0
    for (const [value, count] of sorted) {
      seen += count
      if (index < seen) return value
    }
    return sorted[sorted.length - 1][0]
  }
  return Math.floor((at(Math.floor((n - 1) / 2)) + at(Math.floor(n / 2))) / 2)
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
    ? { type: 'memory_loaded', callId: record.callId, atMs: record.startedMs, callerKey: '', returning: farmer.memory.returning, facts: farmer.memory.facts ?? [], gists: (farmer.memory.gists ?? []).map((g) => ({ ...g, followUps: g.followUps ?? [] })) }
    : null
  const dealView: DealUpdated | null = deal
    ? { type: 'deal_updated', callId: record.callId, atMs: deal.atMs ?? record.endedMs, state: deal.state, commodity: deal.commodity, variety: deal.variety, market: deal.market, quantity: deal.quantity, price: deal.price, unit: deal.unit, pickup: deal.pickup, farmerYes: deal.farmerYes, buyerYes: deal.buyerYes }
    : null
  return {
    callId: record.callId,
    origin: 'stored',
    startedMs: record.startedMs,
    language: record.languages?.farmer ?? record.languages?.buyer ?? '',
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
    recorded: {
      notStored: record.data.notStored ?? {},
      priceReference: record.data.price?.reference ?? null,
      receipt: options.receipt === true,
      termsOnly: termsOnlyDeal(record, deal),
      latency: timings.length === 0 ? storedLatency(record) : null,
    },
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
  const price = deal.price !== null ? dealPrice(deal.price, deal.unit, ' per ') : null
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
  const { deal, farmer, buyer, price } = record.data
  const notStored = record.data.notStored ?? {}
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
    const name = (farmer.memory.facts ?? []).find((f) => f.field === 'name')?.value
    points.push({ id: 'memory', tone: 'plain', text: farmer.memory.returning ? `Returning farmer${name ? ` (${name})` : ''}${farmer.identity === 'confirmed' ? ', name confirmed on the call' : ''}.` : 'First call from this farmer: nothing was remembered yet.' })
  }
  const lookups = (farmer?.tools ?? []).filter((t) => t.ok).length
  if (lookups) points.push({ id: 'tools', tone: 'plain', text: `${lookups} assistant ${lookups === 1 ? 'lookup' : 'lookups'} used.` })
  if (notStored.farmer) points.push({ id: 'ns-farmer', tone: 'plain', text: notStoredText('farmer', notStored.farmer, Boolean(farmer)) })
  if (notStored.buyer) points.push({ id: 'ns-buyer', tone: 'plain', text: notStoredText('buyer', notStored.buyer, Boolean(buyer)) })
  return points
}

export type AnalyticsTab = 'live' | 'calls'

/** "#/analytics" opens the stored calls (and "?id=" one of them); "#/analytics/live" is the live call. */
export function tabOf(param: string | null): AnalyticsTab {
  return param === 'live' ? 'live' : 'calls'
}

/** The backend's 503 code when its one-time privacy cleanup of old rows did not finish. */
export const CLEANUP_FAILED = 'analytics_privacy_cleanup_failed'

/** How long the Calls tab waits before asking again while the privacy cleanup runs. */
export const RETRY_MS = 5000

/** One answer from the backend for the Calls tab: the list and figures, one record, or why they could not load. */
export interface CallsLoad {
  calls?: CallSummary[]
  summary?: AnalyticsSummary
  record?: CallRecordView
  failure: ApiFailure | null
  failureCode: string | null
}

interface Timers {
  setTimer: (fn: () => void, ms: number) => ReturnType<typeof setTimeout>
  clearTimer: (id: ReturnType<typeof setTimeout>) => void
}

const REAL_TIMERS: Timers = { setTimer: (fn, ms) => setTimeout(fn, ms), clearTimer: (id) => clearTimeout(id) }

/**
 * Loads the Calls tab (one record when `callId` is set) and reports each answer. While the backend's one-time privacy
 * cleanup runs (503 in progress) it asks again every {@link RETRY_MS}; a failed cleanup waits for the viewer. The
 * returned stop function clears any pending retry and drops answers that arrive after it.
 */
export function watchCalls(callId: string | null, emit: (load: CallsLoad) => void, timers: Timers = REAL_TIMERS): () => void {
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | null = null
  const run = () => {
    timer = null
    const load: Promise<CallsLoad> = callId
      ? api(`/api/analytics/calls/${encodeURIComponent(callId)}`).then((r) => ({ record: r as CallRecordView, failure: null, failureCode: null }))
      : Promise.all([api('/api/analytics/calls?limit=500'), api('/api/analytics/summary')]).then(([list, s]) => ({
          calls: ((list as { calls?: CallSummary[] } | null)?.calls) ?? [],
          summary: s as AnalyticsSummary,
          failure: null,
          failureCode: null,
        }))
    load
      .then((answer) => { if (!stopped) emit(answer) })
      .catch((err: unknown) => {
        if (stopped) return
        const failure = err instanceof ApiError ? err.failure : 'unreachable'
        const failureCode = err instanceof ApiError ? err.code : null
        emit({ failure, failureCode })
        if (failure === 'unavailable' && failureCode !== CLEANUP_FAILED) timer = timers.setTimer(run, RETRY_MS)
      })
  }
  run()
  return () => {
    stopped = true
    if (timer !== null) timers.clearTimer(timer)
  }
}
