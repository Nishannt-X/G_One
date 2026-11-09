import type { SimulatorState } from './useCallSimulator'

/** What the buyer's phone is showing. */
export type JoinStage =
  | 'bad-link'
  | 'insecure'
  | 'ready'
  | 'connecting'
  | 'waiting'
  | 'in-call'
  | 'reconnecting'
  | 'ended'
  | 'mic-denied'
  | 'refused'
  | 'unreachable'

type CallFacts = Pick<SimulatorState, 'status' | 'micError' | 'refused' | 'lines'>

/** One screen for every combination of link, page security and call state. A dead link or insecure page wins. */
export function joinStage(callId: string | null, secure: boolean, call: CallFacts): JoinStage {
  if (!callId) return 'bad-link'
  if (!secure) return 'insecure'
  switch (call.status) {
    case 'idle':
      return 'ready'
    case 'connecting':
      return 'connecting'
    case 'reconnecting':
      return 'reconnecting'
    case 'live':
      return call.lines.length > 0 ? 'in-call' : 'waiting'
    case 'ended':
      return 'ended'
    case 'error':
      return call.micError ? 'mic-denied' : call.refused ? 'refused' : 'unreachable'
  }
}
