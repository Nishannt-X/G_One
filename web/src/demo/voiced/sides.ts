import type { LegRole } from '../../types/console'
import type { Beat, Clip, Hearing, Manifest } from './manifest'
import type { Cursor } from './timeline'

export function langOf(code: string): string {
  return code.split('-')[0]
}

export const PARTY = { farmer: 'Farmer', buyer: 'Buyer' } as const

/** What the current moment means for one side of the call. Null before the sample starts and after it ends. */
export interface SideNow {
  hearing: Hearing | null
  label: string
  clip: Clip | null
  beat: Beat | null
}

export function currentClip(manifest: Manifest, cursor: Cursor): { beat: Beat | null; clip: Clip | null } {
  const beat = manifest.beats[cursor.beat] ?? null
  return { beat, clip: beat?.clips[cursor.clip] ?? null }
}

export function sideNow(manifest: Manifest, cursor: Cursor, party: LegRole, finished: boolean): SideNow {
  const { beat, clip } = currentClip(manifest, cursor)
  if (finished || !beat || !clip) return { hearing: null, label: finished ? 'Call ended' : 'Waiting', clip: null, beat: null }
  const hearing = clip.hearing[party]
  const name = PARTY[party]
  if (hearing === 'speaking') return { hearing, label: `${name} is speaking`, clip, beat }
  if (hearing === 'hears') {
    const otherLine = clip.hearing[party === 'farmer' ? 'buyer' : 'farmer'] === 'silent'
    return { hearing, label: beat.whisperKind && otherLine ? `Only the ${party} hears this` : `${name} hears this`, clip, beat }
  }
  const buyerOn = manifest.beats.slice(0, cursor.beat + 1).some((b) => b.buyerJoins)
  if (party === 'buyer' && !buyerOn) return { hearing, label: 'Buyer not on the call yet', clip, beat }
  if (party === 'farmer' && beat.notPassedOn) return { hearing, label: 'Not passed on', clip, beat }
  return { hearing, label: `${name} hears nothing`, clip, beat }
}

/** The deal summary has reached this side's phone. Derived from the cursor, so seeking and replay need no undo. */
export function phoneLit(manifest: Manifest, cursor: Cursor, party: LegRole, finished: boolean): boolean {
  const beat = manifest.beats.findIndex((b) => b.deliver === party)
  return beat >= 0 && (finished || cursor.beat >= beat)
}
