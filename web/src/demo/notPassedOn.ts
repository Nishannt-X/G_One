import type { TimelineItem } from '../lib/callState'

/**
 * True when a mediated turn was answered by G-One to the speaker instead of being passed on: the next thing on the
 * timeline is G-One speaking to that same party (a question to G-One, a "please say that again", an end-of-call
 * question), and no translation for it ever arrived.
 */
export function notPassedOn(items: TimelineItem[], key: string, translated: boolean): boolean {
  if (translated) return false
  const at = items.findIndex((x) => x.key === key)
  const turn = items[at]
  if (at < 0 || turn?.kind !== 'turn') return false
  const next = items[at + 1]
  return next?.kind === 'assistant' && next.role === turn.role
}
