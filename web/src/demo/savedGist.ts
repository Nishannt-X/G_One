import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import type { CallView } from '../lib/callState'
import { parseMemory, type MemoryGist, type MemoryRecord } from '../lib/memory'

/**
 * What the backend kept from a finished call. The gist is written after hang-up (often with an LLM summary, which can
 * take several seconds), and no console event announces it, so the page re-reads the farmer's memory until it appears.
 */
export type SavedGist =
  | { state: 'saving' }
  | { state: 'saved'; gist: MemoryGist; openItems: string[] }
  | { state: 'not_saved' }

const POLL_MS = 3000
const GIVE_UP_MS = 60_000

/** The gist written for this call: same call id, or failing that one that was not there when the call began. */
export function findSavedGist(record: MemoryRecord, callId: string, loadedCallIds: ReadonlyArray<string>, startedMs: number): MemoryGist | null {
  return (
    record.gists.find((g) => g.callId === callId) ??
    record.gists.find((g) => !loadedCallIds.includes(g.callId) && g.startedMs >= startedMs - 60_000) ??
    null
  )
}

/** Null while the call runs, for a sample call, or when memory is off (withheld caller ID). */
export function useSavedGist(call: CallView | null): SavedGist | null {
  const callId = call?.callId ?? ''
  const callerKey = call?.memory?.callerKey ?? ''
  const live = call?.origin === 'feed'
  const endedMs = call?.ended?.atMs ?? 0
  const startedMs = call?.startedMs ?? 0
  const loaded = (call?.memory?.gists ?? []).map((g) => g.callId).join('\n')
  const [result, setResult] = useState<{ callId: string; saved: SavedGist } | null>(null)

  useEffect(() => {
    if (!live || !callerKey || !endedMs) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const loadedIds = loaded ? loaded.split('\n') : []
    const deadline = Date.now() + GIVE_UP_MS
    const check = async () => {
      try {
        const record = parseMemory(await api(`/api/memory/${callerKey}`), callerKey)
        const gist = findSavedGist(record, callId, loadedIds, startedMs)
        if (stopped) return
        if (gist) {
          setResult({ callId, saved: { state: 'saved', gist, openItems: gist.followUps } })
          return
        }
      } catch {
        // Not there yet, or the backend is away: try again until the deadline.
      }
      if (stopped) return
      if (Date.now() >= deadline) setResult({ callId, saved: { state: 'not_saved' } })
      else timer = setTimeout(() => void check(), POLL_MS)
    }
    timer = setTimeout(() => void check(), POLL_MS)
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [live, callerKey, endedMs, callId, startedMs, loaded])

  if (!live || !callerKey || !endedMs) return null
  return result?.callId === callId ? result.saved : { state: 'saving' }
}

export function savedGistText(saved: SavedGist): string {
  switch (saved.state) {
    case 'saving': return 'Saving a summary for next time…'
    case 'saved': return `Saved for next time: ${saved.gist.summary}${saved.openItems.length ? ` · Open: ${saved.openItems.join('; ')}` : ''}`
    case 'not_saved': return 'No summary was saved for this call.'
  }
}
