import type { CallView } from '../lib/callState'

export type CreditMode = 'live' | 'sample' | null

/** Which speech models produced the conversation on screen, if any. The text sample uses none, so it gets no credit. */
export function creditMode(voicedSample: boolean, origin: CallView['origin'] | null): CreditMode {
  if (voicedSample) return 'sample'
  return origin === 'feed' ? 'live' : null
}
