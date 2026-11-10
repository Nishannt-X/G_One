import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import type { CallView } from '../lib/callState'
import { parseMemory, type MemoryGist, type MemoryRecord } from '../lib/memory'

/**
 * What the backend kept from a finished call. The gist is written after hang-up (often with an LLM summary, which can
 * take several seconds), and no console event announces it, so the page re-reads the farmer's memory until a gist with
 * this call's id appears. A missing gist is never reported as "not saved": the write may still land later, so the page
 * only says it could not confirm the save, and why.
 */
export type UnconfirmedReason = 'unauthorized' | 'backend' | 'timeout'

export type SavedGist =
  | { state: 'saving' }
  | { state: 'saved'; gist: MemoryGist; openItems: string[] }
  | { state: 'unconfirmed'; reason: UnconfirmedReason }

const POLL_MS = 3000
const GIVE_UP_MS = 60_000

/** The gist written for this call, matched only by its exact call id. */
export function findSavedGist(record: MemoryRecord, callId: string): MemoryGist | null {
  if (!callId) return null
  return record.gists.find((g) => g.callId === callId) ?? null
}

/** Why the last poll failed, in the terms the note shows. */
export function unconfirmedReason(error: unknown): UnconfirmedReason {
  return error instanceof ApiError && error.failure === 'unauthorized' ? 'unauthorized' : 'backend'
}

/** Null while the call runs, for a sample call, or when memory is off (withheld caller ID). */
export function useSavedGist(call: CallView | null): SavedGist | null {
  const callId = call?.callId ?? ''
  const callerKey = call?.memory?.callerKey ?? ''
  const live = call?.origin === 'feed'
  const endedMs = call?.ended?.atMs ?? 0
  const [result, setResult] = useState<{ callId: string; saved: SavedGist } | null>(null)

  useEffect(() => {
    if (!live || !callerKey || !endedMs || !callId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let lastError: UnconfirmedReason | null = null
    const deadline = Date.now() + GIVE_UP_MS
    const check = async () => {
      try {
        const record = parseMemory(await api(`/api/memory/${callerKey}`), callerKey)
        if (stopped) return
        lastError = null
        const gist = findSavedGist(record, callId)
        if (gist) {
          setResult({ callId, saved: { state: 'saved', gist, openItems: gist.followUps } })
          return
        }
      } catch (error) {
        if (stopped) return
        lastError = unconfirmedReason(error)
        // A wrong or missing access code will not fix itself by waiting.
        if (lastError === 'unauthorized') {
          setResult({ callId, saved: { state: 'unconfirmed', reason: 'unauthorized' } })
          return
        }
      }
      if (Date.now() >= deadline) setResult({ callId, saved: { state: 'unconfirmed', reason: lastError ?? 'timeout' } })
      else timer = setTimeout(() => void check(), POLL_MS)
    }
    timer = setTimeout(() => void check(), POLL_MS)
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [live, callerKey, endedMs, callId])

  if (!live || !callerKey || !endedMs || !callId) return null
  return result?.callId === callId ? result.saved : { state: 'saving' }
}

export function savedGistText(saved: SavedGist): string {
  switch (saved.state) {
    case 'saving': return 'Saving a summary for next time…'
    case 'saved': return `Saved for next time: ${saved.gist.summary}${saved.openItems.length ? ` · Open: ${saved.openItems.join('; ')}` : ''}`
    case 'unconfirmed':
      switch (saved.reason) {
        case 'unauthorized': return "Couldn't confirm the summary was saved: the access code was refused. Re-enter it and reload."
        case 'backend': return "Couldn't confirm the summary was saved: the backend did not answer."
        case 'timeout': return "Couldn't confirm the summary was saved yet. Check the farmer's memory profile later."
      }
  }
}
