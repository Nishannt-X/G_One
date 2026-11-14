import { api } from './api'
import type { Origin } from '../types/console'

export interface MemoryFact {
  field: string
  value: string
  source: Origin
  callId: string
  atMs: number
}

export interface MemoryGist {
  callId: string
  startedMs: number
  endedMs: number
  summary: string
  followUps: string[]
}

export interface MemoryRecord {
  callerKey: string
  known: boolean
  facts: MemoryFact[]
  gists: MemoryGist[]
}

function time(value: unknown): number {
  const ms = typeof value === 'string' ? Date.parse(value) : NaN
  return Number.isNaN(ms) ? 0 : ms
}

/** Reads GET /api/memory/{callerKey}. Entries without their main text are skipped. Gists come back newest first. */
export function parseMemory(body: unknown, callerKey: string): MemoryRecord {
  const v = (typeof body === 'object' && body !== null ? body : {}) as Record<string, unknown>
  const items = (x: unknown) => (Array.isArray(x) ? (x as Record<string, unknown>[]).filter((e) => typeof e === 'object' && e !== null) : [])
  return {
    callerKey,
    known: v.known === true,
    facts: items(v.facts)
      .filter((f) => typeof f.field === 'string' && typeof f.value === 'string')
      .map((f) => ({ field: f.field as string, value: f.value as string, source: f.source === 'said' ? 'said' : 'inferred', callId: String(f.callId ?? ''), atMs: time(f.at) })),
    gists: items(v.gists)
      .filter((g) => typeof g.summary === 'string')
      .map((g) => ({
        callId: String(g.callId ?? ''),
        startedMs: time(g.startedAt),
        endedMs: time(g.endedAt),
        summary: g.summary as string,
        followUps: Array.isArray(g.followUps) ? g.followUps.filter((f): f is string => typeof f === 'string') : [],
      }))
      .sort((a, b) => b.startedMs - a.startedMs),
  }
}

/** A caller key is 64 hex characters: the hash of a number, never the number. */
export function isCallerKey(value: string): boolean {
  return /^[0-9a-f]{64}$/i.test(value.trim())
}

/**
 * What a "forget me" (DELETE /api/memory/{key}) answered: "done" (204), or "completing" (202 "accepted_completing"):
 * the linked call records are already unlinked and the memory is removed when the backend retries.
 */
export function forgetResult(body: unknown): 'done' | 'completing' {
  return (body as { status?: unknown } | null)?.status === 'accepted_completing' ? 'completing' : 'done'
}

/** Asks the backend to forget a caller (DELETE /api/memory/{key}); throws an ApiError when it refuses. */
export async function forgetCaller(callerKey: string): Promise<'done' | 'completing'> {
  return forgetResult(await api(`/api/memory/${encodeURIComponent(callerKey)}`, { method: 'DELETE' }))
}

/** The notice after a forget: done, or accepted and still completing (nothing claims the memory is gone yet). */
export function forgetNotice(who: string, result: 'done' | 'completing'): string {
  return result === 'completing'
    ? `Forget accepted for ${who}, completing: their call records are unlinked, and the memory is removed when G-One retries.`
    : `${who} has been forgotten.`
}
