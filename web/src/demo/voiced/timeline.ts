import type { CallView } from '../../lib/callState'
import type { DealUpdated } from '../../types/console'
import type { Beat, Clip, Manifest } from './manifest'

/** One clip placed on the sample's own clock, in seconds at 1x. */
export interface Segment {
  beat: number
  clip: number
  start: number
  end: number
  source: Clip
}

export interface Plan {
  segments: Segment[]
  /** Start of each beat, by beat index. */
  beatStarts: number[]
  total: number
}

export function buildPlan(manifest: Manifest): Plan {
  const segments: Segment[] = []
  const beatStarts: number[] = []
  let at = 0
  manifest.beats.forEach((beat, b) => {
    beatStarts.push(at)
    beat.clips.forEach((source, c) => {
      const end = at + source.durationMs / 1000
      segments.push({ beat: b, clip: c, start: at, end, source })
      at = end + source.pauseAfterMs / 1000
    })
  })
  // The last pause is not worth waiting for.
  const last = segments.at(-1)
  return { segments, beatStarts, total: last ? last.end : 0 }
}

export interface Cursor {
  beat: number
  clip: number
  /** A voice is playing now, rather than the pause between two lines. */
  voicing: boolean
}

export const NOT_STARTED: Cursor = { beat: -1, clip: -1, voicing: false }

/** The line that is playing at `t`, or during a pause, the line that just played. */
export function cursorAt(plan: Plan, t: number): Cursor {
  if (t < 0 || plan.segments.length === 0) return NOT_STARTED
  let low = 0
  let high = plan.segments.length - 1
  while (low < high) {
    const mid = Math.ceil((low + high) / 2)
    if (plan.segments[mid].start <= t) low = mid
    else high = mid - 1
  }
  const segment = plan.segments[low]
  return { beat: segment.beat, clip: segment.clip, voicing: t >= segment.start && t < segment.end }
}

/** The clock position to jump to for "skip": the next beat, or the end when this is the last one. */
export function nextBeatStart(plan: Plan, t: number): number {
  return plan.beatStarts.find((start) => start > t + 0.001) ?? plan.total
}

/** The beats a viewer has reached. A finished sample shows them all. */
export function reached(manifest: Manifest, beat: number): Beat[] {
  return manifest.beats.slice(0, beat + 1)
}

const CALL_ID = 'voiced-sample'
const DAY = 86_400_000

/**
 * The state of the sample call after beat `beat`, in the shape the deal panel reads. Rebuilding it from the beats means
 * restart and skip need no undo.
 */
export function callViewAt(manifest: Manifest, beat: number, startedMs: number, finished: boolean): CallView {
  const beats = reached(manifest, beat)
  let deal: DealUpdated | null = null
  const tools: CallView['tools'] = []
  beats.forEach((step, index) => {
    const atMs = startedMs + index * 1000
    if (step.dealPatch) {
      deal = {
        type: 'deal_updated', callId: CALL_ID, atMs, state: 'draft', commodity: null, variety: null, market: null, quantity: null, price: null, unit: null, pickup: null, farmerYes: false, buyerYes: false,
        ...deal,
        ...step.dealPatch,
      } as DealUpdated
    }
    if (step.tool) tools.push({ key: `tool-${step.id}`, tool: step.tool.tool, args: step.tool.args, ok: true, result: step.tool.result, durationMs: 0, atMs })
  })
  const earlier = startedMs - manifest.memoryBefore.gist.daysAgo * DAY
  const before = { callId: 'voiced-earlier', startedMs: earlier, endedMs: earlier + 240_000, summary: manifest.memoryBefore.gist.summary, followUps: [] }
  const saved = finished ? [{ callId: CALL_ID, startedMs, endedMs: startedMs, summary: manifest.memoryAfter.summary, followUps: [] }] : []
  return {
    callId: CALL_ID,
    origin: 'sample',
    startedMs,
    language: manifest.farmer.language,
    legs: [],
    items: [],
    languageNotes: [],
    tools,
    translations: {},
    timings: [],
    memory: beats.length === 0 ? null : {
      type: 'memory_loaded', callId: CALL_ID, atMs: startedMs, callerKey: 'sample', returning: true,
      facts: manifest.memoryBefore.facts.map((f) => ({ ...f, provenance: { callId: 'voiced-earlier', saidOrInferred: 'said' as const, atMs: earlier } })),
      gists: [...saved, before],
    },
    deal,
    ended: finished ? { atMs: startedMs, durationMs: 0, reason: 'hangup' } : null,
    seq: beats.length,
  }
}
