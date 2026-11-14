import { useEffect } from 'react'
import type { FeedState } from './callState'
import { englishName, englishText } from './farmersScript'
import { languageLabel } from './format'
import type { MemoryFact, MemoryGist, MemoryRecord } from './memory'
import type { Origin } from '../types/console'

/**
 * The backend can read one farmer by caller key but cannot list them, and a key cannot be worked out here because it is
 * salted. So the list is built from callers this browser has watched on the console feed.
 */
export type FarmerOrigin = 'memory' | 'sample' | 'seed'

export interface SeenCaller {
  callerKey: string
  language: string
  seenMs: number
  origin: FarmerOrigin
  /** What a sample call showed. A sample caller is not in the store, so this is all there is. */
  snapshot?: { facts: MemoryFact[]; gists: MemoryGist[] }
}

export interface FarmerDeal {
  id: string
  /** The call the deal was made on. */
  callId?: string
  commodity: string
  quantity: string
  price: number | null
  unit: string
  pickup: string
  state: string
  atMs: number
  /** The market named on the receipt, when there is one. */
  market?: string
  /** The buyer as the farmer may see them: a city and a kind, e.g. "Trader, Pune". Never a phone number. */
  buyer?: string
}

/** One stored fact as the Farmers tab shows it: English letters, with whether the farmer told it. */
export interface FarmerValue {
  value: string
  source: Origin
}

export interface Farmer {
  callerKey: string
  origin: FarmerOrigin
  /** Always English letters (a stored native-script name is transliterated), or null when none was given. */
  name: string | null
  district: string | null
  language: string
  crops: string[]
  lastCallMs: number
  callCount: number
  facts: MemoryFact[]
  gists: MemoryGist[]
  deals?: FarmerDeal[]
  /** The profile fields, each in English letters with its provenance; absent when not told yet. */
  profile: Partial<Record<ProfileField, FarmerValue>>
  /** Each crop with its provenance, English letters. */
  cropFacts: (FarmerValue & { name: string })[]
}

/** The single profile fields the backend stores (memory/ProfileFields.java), in display order. */
export const PROFILE_FIELDS = ['name', 'village', 'district', 'state', 'usualMandi', 'typicalQuantity', 'transport', 'paymentPreference', 'storageAccess', 'language'] as const
export type ProfileField = (typeof PROFILE_FIELDS)[number]

const KEY = 'g-one-seen-callers'
const MAX = 100

export function readSeen(): SeenCaller[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(value) ? (value as SeenCaller[]).filter((c) => typeof c?.callerKey === 'string') : []
  } catch {
    return []
  }
}

function writeSeen(list: SeenCaller[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX)))
  } catch {
    // Storage is blocked: the list lasts for this page view.
  }
}

export function forgetSeen(callerKey: string) {
  writeSeen(readSeen().filter((c) => c.callerKey !== callerKey))
}

/** Puts a caller first, replacing any earlier entry for the same key. */
export function mergeSeen(list: SeenCaller[], next: SeenCaller): SeenCaller[] {
  return [next, ...list.filter((c) => c.callerKey !== next.callerKey)]
}

/** Remembers each caller the feed shows, for the Farmers page. Stores the key (a hash), never a number. */
export function useFarmerRegistry(feed: FeedState) {
  useEffect(() => {
    let list = readSeen()
    let changed = false
    for (const id of feed.order) {
      const call = feed.calls[id]
      const memory = call.memory
      if (!memory?.callerKey) continue
      const known = list.find((c) => c.callerKey === memory.callerKey)
      if (known && known.seenMs >= call.startedMs) continue
      const sample = call.origin !== 'feed'
      list = mergeSeen(list, {
        callerKey: memory.callerKey,
        language: call.language,
        seenMs: call.startedMs,
        origin: sample ? 'sample' : 'memory',
        snapshot: sample
          ? {
              facts: memory.facts.map((f) => ({ field: f.field, value: f.value, source: f.provenance.saidOrInferred, callId: f.provenance.callId, atMs: f.provenance.atMs })),
              gists: memory.gists,
            }
          : undefined,
      })
      changed = true
    }
    if (changed) writeSeen(list)
  }, [feed])
}

const CROP_PREFIX = 'crop:'

/**
 * The farmer's crops, in stored order and without duplicates. The backend keeps one fact per crop, `crop:<name>`
 * (for example `crop:red onion`); older or hand-made records may hold a comma list under `crops`, `crop` or `main_crop`.
 */
export function cropsOf(facts: ReadonlyArray<{ field: string; value: string }>): string[] {
  const out: string[] = []
  const add = (crop: string) => {
    const c = crop.trim()
    if (c && !out.some((o) => o.toLowerCase() === c.toLowerCase())) out.push(c)
  }
  for (const f of facts) {
    if (f.field.startsWith(CROP_PREFIX)) add(f.value.trim() || f.field.slice(CROP_PREFIX.length))
    else if (f.field === 'crops' || f.field === 'crop' || f.field === 'main_crop') f.value.split(/[,/]/).forEach(add)
  }
  return out
}

/** The assistant receives at most five open follow-ups from all stored gists. */
export const MAX_OPEN_FOLLOW_UPS = 5
const MAX_FOLLOW_UP_CHARS = 160

function cleanFollowUp(text: string): string {
  const flat = text.replace(/[\p{Cc}\p{Zl}\p{Zp}]+/gu, ' ').replace(/\s+/g, ' ').trim()
  const points = Array.from(flat)
  return points.length <= MAX_FOLLOW_UP_CHARS ? flat : points.slice(0, MAX_FOLLOW_UP_CHARS).join('').trimEnd() + '…'
}

/**
 * The follow-ups the assistant receives from MemoryBlock: all stored gists, newest first, once each, capped at five.
 * The backend removes DONE items from their original gists, so this set is already reconciled.
 */
export function openFollowUps(gists: ReadonlyArray<{ startedMs: number; followUps: string[] }>): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const g of [...gists].sort((a, b) => b.startedMs - a.startedMs)) {
    for (const item of g.followUps) {
      const key = cleanFollowUp(item)
      if (key && !seen.has(key)) {
        seen.add(key)
        out.push(key)
        if (out.length === MAX_OPEN_FOLLOW_UPS) return out
      }
    }
  }
  return out
}

/** The backend's stored language codes as the console's language codes. */
const LANGUAGE_CODES: Record<string, string> = { HI: 'hi-IN', EN: 'en-IN', HI_EN: 'hi-en', BN: 'bn-IN' }

function profileOf(facts: MemoryFact[]): Farmer['profile'] {
  const out: Farmer['profile'] = {}
  for (const field of PROFILE_FIELDS) {
    const fact = facts.find((f) => f.field === field)
    if (!fact) continue
    const raw = fact.value.trim()
    const value = field === 'language' ? (LANGUAGE_CODES[raw.toUpperCase()] ?? raw) : field === 'name' ? englishName(raw) : englishText(raw)
    if (value) out[field] = { value, source: fact.source }
  }
  return out
}

function cropFactsOf(facts: MemoryFact[]): Farmer['cropFacts'] {
  const out: Farmer['cropFacts'] = []
  for (const f of facts) {
    const names = f.field.startsWith(CROP_PREFIX) ? [f.value.trim() || f.field.slice(CROP_PREFIX.length)] : f.field === 'crops' || f.field === 'crop' || f.field === 'main_crop' ? f.value.split(/[,/]/) : []
    for (const n of names) {
      const name = englishText(n.trim())
      if (name && !out.some((c) => c.name.toLowerCase() === name.toLowerCase())) out.push({ name, value: name, source: f.source })
    }
  }
  return out
}

export function toFarmer(callerKey: string, origin: FarmerOrigin, language: string, seenMs: number, facts: MemoryFact[], gists: MemoryGist[], deals?: FarmerDeal[]): Farmer {
  const profile = profileOf(facts)
  const cropFacts = cropFactsOf(facts)
  return {
    callerKey,
    origin,
    name: profile.name?.value ?? null,
    district: profile.district?.value ?? profile.village?.value ?? null,
    language: profile.language?.value ?? language,
    crops: cropFacts.map((c) => c.name),
    lastCallMs: gists[0]?.startedMs || seenMs,
    callCount: gists.length,
    facts,
    gists,
    deals,
    profile,
    cropFacts,
  }
}

/** "Nashik, Maharashtra": where the farmer is, from the district (or village) and state. */
export function placeOf(farmer: Farmer): string | null {
  const parts = [farmer.district, farmer.profile.state?.value].filter((p): p is string => Boolean(p))
  return parts.length > 0 ? parts.join(', ') : null
}

/** The first three crops, and how many more there are: ["onion", "tomato", "garlic"] and 2. */
export function cropSummary(crops: string[], max = 3): { shown: string[]; more: number } {
  return { shown: crops.slice(0, max), more: Math.max(0, crops.length - max) }
}

/** Reads GET /api/deals/{callId}/receipt for the Farmers tab; null when the body is not a receipt. */
export function parseReceipt(body: unknown, callId: string): FarmerDeal | null {
  if (typeof body !== 'object' || body === null) return null
  const r = body as Record<string, unknown>
  if (typeof r.commodity !== 'string' || !r.commodity) return null
  const text = (v: unknown) => (typeof v === 'string' ? v : '')
  return {
    id: text(r.id) || callId,
    callId,
    commodity: r.commodity,
    quantity: text(r.quantity),
    price: typeof r.price === 'number' ? r.price : null,
    unit: text(r.unit),
    pickup: text(r.pickup),
    state: text(r.state),
    atMs: typeof r.atMs === 'number' ? r.atMs : 0,
    market: text(r.market) || undefined,
  }
}

/** How a call ended, as the call log shows it: the deal made on it, or none. */
export function callOutcome(callId: string, deals: FarmerDeal[]): { label: string; tone: 'ok' | 'warn' | 'plain' } {
  const deal = deals.find((d) => d.callId === callId || d.id === callId)
  if (!deal) return { label: 'No deal', tone: 'plain' }
  if (deal.state === 'agreed') return { label: 'Deal agreed', tone: 'ok' }
  return { label: deal.state ? `Deal ${deal.state.replace(/_/g, ' ')}` : 'Deal discussed', tone: 'warn' }
}

export function fromRecord(seen: SeenCaller, record: MemoryRecord): Farmer {
  return toFarmer(seen.callerKey, seen.origin, seen.language, seen.seenMs, record.facts, record.gists)
}

/** "Caller ••••a1b2": enough to tell callers apart. The number itself is never stored. */
export function maskedKey(callerKey: string): string {
  return `Caller ••••${callerKey.slice(-4)}`
}

export function searchFarmers(farmers: Farmer[], query: string): Farmer[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return farmers
  return farmers.filter((f) => [f.name ?? '', f.district ?? '', f.profile.village?.value ?? '', f.profile.state?.value ?? '', f.language, languageLabel(f.language), maskedKey(f.callerKey), ...f.crops].some((s) => s.toLowerCase().includes(needle)))
}
