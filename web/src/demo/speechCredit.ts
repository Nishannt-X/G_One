import type { CallView } from '../lib/callState'

export type CreditMode = 'live' | 'sample' | null

/**
 * Which speech models produced the conversation on screen, if any. The offline demo and the text sample use no speech
 * service, so they get no credit.
 */
export function creditMode(voicedSample: boolean, origin: CallView['origin'] | null, offlineDemo: boolean): CreditMode {
  if (voicedSample) return 'sample'
  return origin === 'feed' && !offlineDemo ? 'live' : null
}
