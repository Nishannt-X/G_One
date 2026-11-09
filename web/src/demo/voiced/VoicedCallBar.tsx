import type { LegRole } from '../../types/console'
import { Avatar, Waveform } from '../parts'
import type { VoicedState } from './engine'
import type { Beat, Clip, Manifest } from './manifest'
import { EarChip } from './parts'
import { currentClip, sideNow } from './sides'

function phaseText(beat: Beat | null, clip: Clip | null, finished: boolean): string {
  if (finished) return 'The call has ended'
  if (!beat || !clip) return 'Ready'
  if (clip.kind === 'heard') return `Translating for the ${clip.to}`
  if (clip.kind === 'said') {
    if (beat.whisperKind === 'read_back') return `Reading back to the ${clip.to}`
    if (beat.whisperKind) return `Whispering to the ${clip.to}`
    return `Speaking to the ${clip.to}`
  }
  if (beat.notPassedOn) return 'Holding this back'
  return `Listening to the ${beat.speakerRole}`
}

function Side({ role, manifest, state }: { role: LegRole; manifest: Manifest; state: VoicedState }) {
  const now = sideNow(manifest, state.cursor, role, state.finished)
  const right = role === 'buyer'
  const speaking = now.hearing === 'speaking' && state.cursor.voicing
  const party = role === 'farmer' ? manifest.farmer : manifest.buyer
  return (
    <div className={`flex min-w-0 flex-1 items-center gap-2.5 ${right ? 'flex-row-reverse text-right' : ''}`}>
      <span className="hidden sm:block"><Avatar who={role} speaking={speaking} /></span>
      <div className="min-w-0">
        <p className="truncate text-[0.9375rem] leading-tight font-semibold sm:text-lg">{party.name}</p>
        <p className="truncate text-xs leading-tight text-muted sm:text-sm">{party.languages.join(' · ')}</p>
      </div>
      <Waveform active={speaking} className={`hidden xl:flex ${right ? 'text-buyer' : 'text-farmer'}`} />
      <span className="hidden sm:inline-flex"><EarChip party={role} now={now} compact /></span>
    </div>
  )
}

export default function VoicedCallBar({ manifest, state }: { manifest: Manifest; state: VoicedState }) {
  const { beat, clip } = currentClip(manifest, state.cursor)
  const busy = state.cursor.voicing && clip?.kind !== 'original'
  return (
    <div className="flex items-center gap-2 border-b border-line px-3 py-2.5 sm:gap-4 sm:px-5">
      <Side role="farmer" manifest={manifest} state={state} />
      <div className="flex w-28 shrink-0 flex-col items-center sm:w-52">
        <span className="flex items-center gap-1.5"><Avatar who="assistant" size="sm" speaking={busy} /><span className="font-semibold">G-One</span></span>
        <span aria-live="polite" className="w-full truncate text-center text-xs leading-tight text-muted sm:text-sm">{phaseText(beat, clip, state.finished)}</span>
      </div>
      <Side role="buyer" manifest={manifest} state={state} />
    </div>
  )
}
