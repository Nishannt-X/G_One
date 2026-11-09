import type { LegRole } from '../../types/console'
import { Waveform } from '../parts'
import type { VoicedState } from './engine'
import type { Manifest } from './manifest'
import { EarChip } from './parts'
import { langOf, sideNow } from './sides'

const SPEAKER = { farmer: 'Left speaker', buyer: 'Right speaker' }

/** One person's phone during the sample: what reaches them right now, or the hatching when nothing does. */
function Panel({ party, manifest, state }: { party: LegRole; manifest: Manifest; state: VoicedState }) {
  const now = sideNow(manifest, state.cursor, party, state.finished)
  const whisper = now.hearing === 'hears' && now.beat?.whisperKind && now.beat.whisperKind !== 'read_back'
  const fill =
    now.hearing === 'silent' ? 'hatch-quiet' :
    whisper ? 'bg-whisper-soft' :
    now.hearing === 'hears' ? (party === 'farmer' ? 'bg-farmer-soft' : 'bg-buyer-soft') : ''
  const active = (now.hearing === 'hears' || now.hearing === 'speaking') && state.cursor.voicing
  return (
    <div data-side={party} className={`flex h-[8.5rem] min-w-0 flex-col gap-1.5 overflow-hidden p-3 transition-colors duration-200 md:h-[10.25rem] ${fill}`}>
      <div className="flex min-w-0 items-center justify-between gap-2">
        <EarChip party={party} now={now} />
        {now.hearing !== 'silent' && now.hearing !== null && <span className="hidden shrink-0 items-center gap-2 text-xs font-semibold text-muted sm:flex"><Waveform active={active} className={party === 'farmer' ? 'text-farmer' : 'text-buyer'} /> <span className="min-[1400px]:hidden">{SPEAKER[party]}</span></span>}
      </div>
      {now.clip && now.hearing !== 'silent' ? (
        <p key={now.clip.id} lang={langOf(now.clip.language)} className="line-clamp-3 text-[0.9375rem] leading-snug font-semibold md:line-clamp-4 md:text-lg md:leading-snug">{now.clip.text}</p>
      ) : state.finished ? (
        <p className="text-sm font-semibold text-muted md:text-base">{manifest[party].languages.join(' · ')}</p>
      ) : (
        <span className="flex-1" />
      )}
    </div>
  )
}

export default function HearPanels({ manifest, state }: { manifest: Manifest; state: VoicedState }) {
  return (
    <div className="grid grid-cols-2 divide-x divide-line border-t border-line bg-page/60">
      <Panel party="farmer" manifest={manifest} state={state} />
      <Panel party="buyer" manifest={manifest} state={state} />
    </div>
  )
}
