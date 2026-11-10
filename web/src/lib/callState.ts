import {
  isConsoleEvent,
  type DealUpdated,
  type LegRole,
  type LegSource,
  type MemoryLoaded,
  type ConsoleEvent,
  type WhisperKind,
} from '../types/console'

/** Where a call's events came from: the real feed, the dev-only mock replayer, or the backend's synthetic replay. */
export type CallOrigin = 'feed' | 'mock' | 'sample'

export interface LegView {
  legId: string
  role: LegRole
  source: LegSource
  language: string
  joinedMs: number
  ended: { atMs: number; reason: string } | null
}

export interface Translation {
  toRole: LegRole
  toLanguage: string
  text: string
}

export type TimelineItem =
  | { kind: 'turn'; key: string; legId: string; role: LegRole; language: string; text: string; startedMs: number; atMs: number }
  | { kind: 'assistant'; key: string; legId: string; role: LegRole; language: string; text: string; atMs: number }
  | { kind: 'whisper'; key: string; legId: string; whisperKind: WhisperKind; language: string; text: string; atMs: number }

/** A leg's language being chosen or changed, shown as a small note on the conversation. */
export interface LanguageNote {
  key: string
  role: LegRole
  from: string
  to: string
  atMs: number
}

export interface ToolView {
  key: string
  tool: string
  args: Record<string, unknown> | null
  ok: boolean
  result: string
  durationMs: number
  atMs: number
}

/** Server-clock times for one heard turn. Null until that step has happened. */
export interface TurnTiming {
  key: string
  legId: string
  role: LegRole
  startedMs: number
  endedMs: number
  heardMs: number
  translatedMs: number | null
  repliedMs: number | null
}

export interface CallView {
  callId: string
  origin: CallOrigin
  startedMs: number
  language: string
  legs: LegView[]
  items: TimelineItem[]
  languageNotes: LanguageNote[]
  tools: ToolView[]
  /** Translations by `translationKey(legId, turnStartedMs)`, so they can arrive in any order. */
  translations: Record<string, Translation>
  timings: TurnTiming[]
  memory: MemoryLoaded | null
  deal: DealUpdated | null
  ended: { atMs: number; durationMs: number; reason: string } | null
  /** Counts the events applied to this call; makes each timeline entry's key unique. */
  seq: number
}

export interface FeedState {
  calls: Record<string, CallView>
  /** Call ids, oldest first. */
  order: string[]
}

export type FeedAction =
  | { type: 'event'; event: ConsoleEvent; mock?: boolean }
  /** The feed reconnected and the server is about to resend the running calls: forget the feed's calls. */
  | { type: 'reset' }

export const initialFeed: FeedState = { calls: {}, order: [] }

/** Bounds browser memory on a very long call. */
const MAX_ITEMS = 400
const MAX_CALLS = 20

export function translationKey(legId: string, turnStartedMs: number): string {
  return `${legId}:${turnStartedMs}`
}

function originOf(callId: string, mock: boolean): CallOrigin {
  if (mock) return 'mock'
  return callId.startsWith('replay-') ? 'sample' : 'feed'
}

function cap<T>(list: T[], next: T, max: number): T[] {
  const grown = [...list, next]
  return grown.length > max ? grown.slice(grown.length - max) : grown
}

/** The assistant's next words on a leg answer the newest turn heard on it, if that turn has no answer yet. */
function markReplied(timings: TurnTiming[], legId: string, atMs: number): TurnTiming[] {
  for (let i = timings.length - 1; i >= 0; i--) {
    const timing = timings[i]
    if (timing.legId !== legId) continue
    if (timing.repliedMs !== null) return timings
    const next = [...timings]
    next[i] = { ...timing, repliedMs: atMs }
    return next
  }
  return timings
}

function applyToCall(call: CallView, event: ConsoleEvent): CallView {
  const key = `${call.seq}-${event.type}`
  switch (event.type) {
    case 'call_started':
      return call
    case 'leg_joined': {
      const leg: LegView = {
        legId: event.legId,
        role: event.role,
        source: event.source,
        language: event.language,
        joinedMs: event.atMs,
        ended: null,
      }
      return { ...call, legs: [...call.legs.filter((l) => l.legId !== event.legId), leg] }
    }
    case 'leg_ended':
      return {
        ...call,
        legs: call.legs.map((l) => (l.legId === event.legId ? { ...l, ended: { atMs: event.atMs, reason: event.reason } } : l)),
      }
    case 'turn_heard':
      return {
        ...call,
        timings: cap(
          call.timings,
          {
            key: translationKey(event.legId, event.startedMs),
            legId: event.legId,
            role: event.role,
            startedMs: event.startedMs,
            endedMs: event.endedMs,
            heardMs: event.atMs,
            translatedMs: null,
            repliedMs: null,
          },
          MAX_ITEMS,
        ),
        items: cap(
          call.items,
          { kind: 'turn', key, legId: event.legId, role: event.role, language: event.language, text: event.text, startedMs: event.startedMs, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'turn_translated':
      return {
        ...call,
        timings: call.timings.map((t) =>
          t.key === translationKey(event.legId, event.turnStartedMs) && t.translatedMs === null ? { ...t, translatedMs: event.atMs } : t,
        ),
        translations: {
          ...call.translations,
          [translationKey(event.legId, event.turnStartedMs)]: { toRole: event.toRole, toLanguage: event.toLanguage, text: event.text },
        },
      }
    case 'assistant_said':
      return {
        ...call,
        timings: markReplied(call.timings, event.legId, event.atMs),
        items: cap(
          call.items,
          { kind: 'assistant', key, legId: event.legId, role: event.role, language: event.language, text: event.text, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'whisper_sent':
      return {
        ...call,
        items: cap(
          call.items,
          { kind: 'whisper', key, legId: event.legId, whisperKind: event.kind, language: event.language, text: event.text, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'tool_called':
      return {
        ...call,
        tools: cap(
          call.tools,
          { key, tool: event.tool, args: event.args, ok: event.ok, result: event.result, durationMs: event.durationMs, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'memory_loaded':
      return { ...call, memory: event }
    case 'deal_updated':
      return { ...call, deal: event }
    case 'call_ended':
      return { ...call, ended: { atMs: event.atMs, durationMs: event.durationMs, reason: event.reason } }
    case 'turn_measured':
    case 'consent_recorded':
      return call
    case 'language_changed':
      return {
        ...call,
        language: event.role === 'farmer' ? event.to : call.language,
        legs: call.legs.map((leg) => (leg.legId === event.legId ? { ...leg, language: event.to } : leg)),
        languageNotes: cap(call.languageNotes, { key, role: event.role, from: event.from, to: event.to, atMs: event.atMs }, MAX_ITEMS),
      }
  }
}

export function feedReducer(state: FeedState, action: FeedAction): FeedState {
  if (action.type === 'reset') {
    // The server resends only running calls, so finished ones stay on screen and running ones are rebuilt.
    const kept = state.order.filter((id) => state.calls[id].origin === 'mock' || state.calls[id].ended !== null)
    return { calls: Object.fromEntries(kept.map((id) => [id, state.calls[id]])), order: kept }
  }
  const { event } = action
  if (event.type === 'call_started') {
    const call: CallView = {
      callId: event.callId,
      origin: originOf(event.callId, action.mock === true),
      startedMs: event.atMs,
      language: event.language,
      legs: [],
      items: [],
      languageNotes: [],
      tools: [],
      translations: {},
      timings: [],
      memory: null,
      deal: null,
      ended: null,
      seq: 0,
    }
    const order = [...state.order.filter((id) => id !== event.callId), event.callId]
    const calls = { ...state.calls, [event.callId]: call }
    while (order.length > MAX_CALLS) delete calls[order.shift()!]
    return { calls, order }
  }
  const existing = state.calls[event.callId]
  // The server resends a running call from its call_started, so an event for an unknown call is stale: ignore it.
  if (!existing) return state
  return { ...state, calls: { ...state.calls, [event.callId]: applyToCall({ ...existing, seq: existing.seq + 1 }, event) } }
}

export function legByRole(call: CallView, role: LegRole): LegView | undefined {
  return call.legs.find((l) => l.role === role)
}

/** The call to show: the pinned one, else the newest running call, else the newest call. */
/**
 * The call a page follows: the pinned one, else the newest call still running, else the newest call. "Newest" is the
 * call's own start time, not when it reached this tab: a console reconnect resends running calls and appends them to
 * `order`, which would otherwise put an older call ahead of one started after it.
 */
export function pickCall(state: FeedState, pinned: string | null): CallView | null {
  if (pinned && state.calls[pinned]) return state.calls[pinned]
  const newestFirst = state.order
    .map((id, arrived) => ({ call: state.calls[id], arrived }))
    .sort((a, b) => b.call.startedMs - a.call.startedMs || b.arrived - a.arrived)
    .map((entry) => entry.call)
  return newestFirst.find((c) => !c.ended) ?? newestFirst[0] ?? null
}

/** How long the assistant's words take to say, for the speaking indicator. An estimate: audio is not on the console feed. */
export function speechEstimateMs(text: string): number {
  return Math.min(9000, Math.max(1200, text.length * 75))
}

export type CallPhase =
  | { kind: 'ended' }
  | { kind: 'listening' }
  | { kind: 'translating'; from: LegRole }
  | { kind: 'whisper' }
  | { kind: 'speaking'; to: LegRole }
  | { kind: 'tool'; tool: string }

const TOOL_SHOWN_MS = 1500
const TURN_SHOWN_MS = 1400
const TRANSLATION_WAIT_MS = 8000

/** What the assistant appears to be doing right now, worked out from the newest events. */
export function callPhase(call: CallView, now: number): CallPhase {
  if (call.ended) return { kind: 'ended' }
  const last = call.items[call.items.length - 1]
  const tool = call.tools[call.tools.length - 1]
  if (last?.kind === 'whisper' && now - last.atMs < speechEstimateMs(last.text)) return { kind: 'whisper' }
  if (tool && now - tool.atMs < TOOL_SHOWN_MS && (!last || tool.atMs >= last.atMs)) return { kind: 'tool', tool: tool.tool }
  if (last?.kind === 'assistant' && now - last.atMs < speechEstimateMs(last.text)) return { kind: 'speaking', to: last.role }
  if (last?.kind === 'turn' && now - last.atMs < TRANSLATION_WAIT_MS && !call.translations[translationKey(last.legId, last.startedMs)]) {
    const other: LegRole = last.role === 'farmer' ? 'buyer' : 'farmer'
    const listener = legByRole(call, other)
    if (listener && !listener.ended) return { kind: 'translating', from: last.role }
  }
  return { kind: 'listening' }
}

export type LegActivity = 'assistant' | 'whisper' | 'person' | 'idle'

/** What is happening on one leg now, for its indicator. */
export function legActivity(call: CallView, role: LegRole, now: number): LegActivity {
  if (call.ended || legByRole(call, role)?.ended) return 'idle'
  for (let i = call.items.length - 1; i >= 0; i--) {
    const item = call.items[i]
    if (item.kind === 'whisper') {
      if (role === 'farmer' && now - item.atMs < speechEstimateMs(item.text)) return 'whisper'
    } else if (item.role === role) {
      if (item.kind === 'assistant') return now - item.atMs < speechEstimateMs(item.text) ? 'assistant' : 'idle'
      return now - item.atMs < TURN_SHOWN_MS ? 'person' : 'idle'
    }
    if (now - item.atMs > 10_000) return 'idle'
  }
  return 'idle'
}

const ROLES: readonly string[] = ['farmer', 'buyer']
const WHISPER_KINDS: readonly string[] = ['price_reference', 'caution', 'tip']
const DEAL_STATES: readonly string[] = ['draft', 'readback', 'confirming', 'agreed', 'closed']

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function validFact(value: unknown): boolean {
  const fact = asRecord(value)
  const provenance = asRecord(fact?.provenance)
  return typeof fact?.field === 'string' && typeof fact.value === 'string' && typeof provenance?.callId === 'string' && typeof provenance.atMs === 'number'
}

function validGist(value: unknown): boolean {
  const gist = asRecord(value)
  return typeof gist?.callId === 'string' && typeof gist.summary === 'string' && typeof gist.startedMs === 'number'
}

/**
 * Checks one message from the feed: an unknown type or an event without the fields its view needs is dropped (null),
 * and fields that only decorate it get defaults, so a newer or older backend cannot break the console.
 */
export function normalizeEvent(value: unknown): ConsoleEvent | null {
  if (!isConsoleEvent(value)) return null
  const raw = value as unknown as Record<string, unknown>
  if (typeof raw.callId !== 'string') return null
  const atMs = typeof raw.atMs === 'number' ? raw.atMs : Date.now()
  const base = { ...raw, atMs }
  const language = typeof raw.language === 'string' ? raw.language : ''
  switch (value.type) {
    case 'call_started':
      return { ...base, language } as ConsoleEvent
    case 'leg_joined':
      if (typeof raw.legId !== 'string' || !ROLES.includes(String(raw.role))) return null
      return { ...base, language, source: raw.source === 'twilio' ? 'twilio' : 'simulator' } as ConsoleEvent
    case 'leg_ended':
      if (typeof raw.legId !== 'string') return null
      return { ...base, reason: String(raw.reason ?? '') } as ConsoleEvent
    case 'turn_heard':
      if (typeof raw.legId !== 'string' || typeof raw.text !== 'string' || !ROLES.includes(String(raw.role))) return null
      return { ...base, language, startedMs: typeof raw.startedMs === 'number' ? raw.startedMs : atMs, endedMs: typeof raw.endedMs === 'number' ? raw.endedMs : atMs } as ConsoleEvent
    case 'turn_translated':
      if (typeof raw.legId !== 'string' || typeof raw.text !== 'string' || typeof raw.turnStartedMs !== 'number') return null
      return { ...base, toLanguage: typeof raw.toLanguage === 'string' ? raw.toLanguage : '' } as ConsoleEvent
    case 'assistant_said':
      if (typeof raw.legId !== 'string' || typeof raw.text !== 'string' || !ROLES.includes(String(raw.role))) return null
      return { ...base, language } as ConsoleEvent
    case 'whisper_sent':
      if (typeof raw.text !== 'string') return null
      return { ...base, language, legId: String(raw.legId ?? ''), kind: WHISPER_KINDS.includes(String(raw.kind)) ? raw.kind : 'tip' } as ConsoleEvent
    case 'tool_called':
      if (typeof raw.tool !== 'string') return null
      return { ...base, ok: raw.ok === true, result: String(raw.result ?? ''), durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : 0, args: asRecord(raw.args) } as ConsoleEvent
    case 'memory_loaded':
      return {
        ...base,
        callerKey: String(raw.callerKey ?? ''),
        returning: raw.returning === true,
        facts: Array.isArray(raw.facts) ? raw.facts.filter(validFact) : [],
        gists: (Array.isArray(raw.gists) ? raw.gists.filter(validGist) : []).map((g) => ({ followUps: [], ...(g as object) })),
      } as unknown as ConsoleEvent
    case 'deal_updated':
      return {
        ...base,
        state: DEAL_STATES.includes(String(raw.state)) ? raw.state : 'draft',
        price: typeof raw.price === 'number' ? raw.price : null,
        farmerYes: raw.farmerYes === true,
        buyerYes: raw.buyerYes === true,
      } as ConsoleEvent
    case 'call_ended':
      return { ...base, durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : 0, reason: String(raw.reason ?? '') } as ConsoleEvent
    case 'language_changed':
      if (typeof raw.legId !== 'string' || typeof raw.to !== 'string' || !ROLES.includes(String(raw.role))) return null
      return { ...base, from: String(raw.from ?? ''), reason: String(raw.reason ?? '') } as ConsoleEvent
    case 'consent_recorded':
      if (!ROLES.includes(String(raw.role))) return null
      return { ...base, consent: String(raw.consent ?? 'NONE') } as ConsoleEvent
    case 'turn_measured':
      return {
        ...base,
        handler: String(raw.handler ?? ''),
        model: String(raw.model ?? ''),
        promptTokens: typeof raw.promptTokens === 'number' ? raw.promptTokens : 0,
        completionTokens: typeof raw.completionTokens === 'number' ? raw.completionTokens : 0,
        modelCalls: typeof raw.modelCalls === 'number' ? raw.modelCalls : 0,
        latencyMs: typeof raw.latencyMs === 'number' ? raw.latencyMs : 0,
        failure: String(raw.failure ?? ''),
      } as ConsoleEvent
  }
}
