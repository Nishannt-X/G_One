import { useEffect } from 'react'
import type { FeedState } from './callState'
import type { MemoryFact, MemoryGist, MemoryRecord } from './memory'

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
  commodity: string
  quantity: string
  price: number | null
  unit: string
  pickup: string
  state: string
  atMs: number
}

export interface Farmer {
  callerKey: string
  origin: FarmerOrigin
  name: string | null
  district: string | null
  language: string
  crops: string[]
  lastCallMs: number
  callCount: number
  facts: MemoryFact[]
  gists: MemoryGist[]
  deals?: FarmerDeal[]
}

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

function factValue(facts: MemoryFact[], ...fields: string[]): string | null {
  for (const field of fields) {
    const fact = facts.find((f) => f.field === field)
    if (fact) return fact.value
  }
  return null
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

/**
 * The follow-ups the assistant receives from MemoryBlock: all stored gists, newest first, once each, capped at five.
 * The backend removes DONE items from their original gists, so this set is already reconciled.
 */
export function openFollowUps(gists: ReadonlyArray<{ startedMs: number; followUps: string[] }>): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const g of [...gists].sort((a, b) => b.startedMs - a.startedMs)) {
    for (const item of g.followUps) {
      const key = item.trim().replace(/\s+/g, ' ').toLowerCase()
      if (key && !seen.has(key)) {
        seen.add(key)
        out.push(item)
        if (out.length === MAX_OPEN_FOLLOW_UPS) return out
      }
    }
  }
  return out
}

export function toFarmer(callerKey: string, origin: FarmerOrigin, language: string, seenMs: number, facts: MemoryFact[], gists: MemoryGist[], deals?: FarmerDeal[]): Farmer {
  return {
    callerKey,
    origin,
    name: factValue(facts, 'name'),
    district: factValue(facts, 'district', 'village'),
    language: factValue(facts, 'language') ?? language,
    crops: cropsOf(facts),
    lastCallMs: gists[0]?.startedMs || seenMs,
    callCount: gists.length,
    facts,
    gists,
    deals,
  }
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
  return farmers.filter((f) => [f.name ?? '', f.district ?? '', f.language, maskedKey(f.callerKey), ...f.crops].some((s) => s.toLowerCase().includes(needle)))
}
