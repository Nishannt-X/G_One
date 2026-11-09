import type { TurnTiming } from './callState'

export interface TurnStages {
  /** End of speech to transcript ready. */
  transcript: number
  /** Transcript ready to translation ready. */
  translation: number | null
  /** Transcript ready to the assistant's answer. */
  reply: number | null
}

function nonNegative(ms: number): number {
  return Math.max(0, ms)
}

export function stagesOf(t: TurnTiming): TurnStages {
  return {
    transcript: nonNegative(t.heardMs - t.endedMs),
    translation: t.translatedMs === null ? null : nonNegative(t.translatedMs - t.heardMs),
    reply: t.repliedMs === null ? null : nonNegative(t.repliedMs - t.heardMs),
  }
}

export function median(values: number[]): number | null {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

export interface LatencySummary {
  /** The newest turn's stages. Later stages may still be null while it is in progress. */
  last: TurnStages | null
  median: { transcript: number | null; translation: number | null; reply: number | null }
}

export function summarize(timings: TurnTiming[]): LatencySummary {
  const stages = timings.map(stagesOf)
  const present = (values: (number | null)[]) => values.filter((v): v is number => v !== null)
  return {
    last: stages[stages.length - 1] ?? null,
    median: {
      transcript: median(stages.map((s) => s.transcript)),
      translation: median(present(stages.map((s) => s.translation))),
      reply: median(present(stages.map((s) => s.reply))),
    },
  }
}
