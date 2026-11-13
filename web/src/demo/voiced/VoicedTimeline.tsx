import { useStickToBottom } from '../../lib/useStickToBottom'
import { CheckIcon, CrossIcon, EarIcon, EarOffIcon, LockIcon, MemoryIcon, RestartIcon, SearchIcon, StoreIcon } from '../../ui/icons'
import { Avatar, Waveform } from '../parts'
import { Note } from '../Timeline'
import { toolNote } from '../toolNote'
import type { VoicedState } from './engine'
import type { Beat, BeatKind, Clip, Manifest } from './manifest'
import { langOf, PARTY } from './sides'

const KIND_LABEL: Record<BeatKind, string> = { consent: 'Consent', price_reference: 'Price note', caution: 'Caution', read_back: 'Read-back' }

interface Stage {
  /** This beat is the one playing. */
  current: boolean
  /** Index of the clip playing in this beat, or -1. */
  clip: number
  isRevealed: (clip: number) => boolean
}

function Line({ clip, big }: { clip: Clip; big: boolean }) {
  return (
    <>
      <p lang={langOf(clip.language)} className={big ? 'text-xl leading-snug font-semibold' : 'text-lg leading-snug font-semibold'}>{clip.text}</p>
      {clip.gloss && <p lang="en" className="mt-0.5 text-base leading-snug text-muted">{clip.gloss}</p>}
    </>
  )
}

function Heard({ clip, active, shown }: { clip: Clip; active: boolean; shown: boolean }) {
  const to = clip.to ?? 'farmer'
  const tone = to === 'farmer' ? 'bg-farmer-soft ring-farmer' : 'bg-buyer-soft ring-buyer'
  const text = to === 'farmer' ? 'text-farmer' : 'text-buyer'
  return (
    <div data-clip={clip.id} data-heard="true" className={`mt-2 rounded-xl px-3 py-2 ring-inset transition-opacity duration-300 ${tone} ${active ? 'ring-2' : ''} ${shown ? 'opacity-100' : 'opacity-0'}`}>
      <p className={`flex items-center gap-1.5 text-sm font-bold ${text}`}><EarIcon /> {PARTY[to]} hears · {clip.languages.join(' · ')}</p>
      <Line clip={clip} big={false} />
    </div>
  )
}

function Turn({ beat, stage }: { beat: Beat; stage: Stage }) {
  const role = beat.speakerRole === 'buyer' ? 'buyer' : 'farmer'
  const right = role === 'buyer'
  const originals = beat.clips.filter((c) => c.kind === 'original')
  const heard = beat.clips.find((c) => c.kind === 'heard')
  const speaking = stage.current && beat.clips[stage.clip]?.kind === 'original'
  const ring = stage.current ? (right ? 'ring-2 ring-buyer' : 'ring-2 ring-farmer') : ''
  return (
    <div className={`animate-rise flex gap-3 ${right ? 'flex-row-reverse pl-8 lg:ml-auto lg:w-1/2 lg:pl-8' : 'pr-8 lg:w-1/2 lg:pr-8'}`}>
      <Avatar who={role} size="sm" speaking={speaking} />
      <div data-beat={beat.id} className={`min-w-0 rounded-2xl px-4 py-3 transition-shadow duration-200 ${right ? 'rounded-tr-md bg-buyer-soft' : 'rounded-tl-md bg-farmer-soft'} ${ring}`}>
        <p className="flex items-center justify-between gap-2 text-sm font-bold text-muted">
          <span>{PARTY[role]} says · {[...new Set(originals.flatMap((c) => c.languages))].join(' · ')}</span>
          <Waveform active={speaking} className={`shrink-0 ${right ? 'text-buyer' : 'text-farmer'}`} />
        </p>
        {originals.map((clip) => (
          <div key={clip.id} data-clip={clip.id} className={`${beat.clips.indexOf(clip) > 0 ? 'mt-1.5' : ''} transition-opacity duration-300 ${stage.isRevealed(beat.clips.indexOf(clip)) ? 'opacity-100' : 'opacity-30'}`}>
            <Line clip={clip} big />
          </div>
        ))}
        {heard && <Heard clip={heard} active={stage.current && beat.clips[stage.clip] === heard} shown={stage.isRevealed(beat.clips.indexOf(heard))} />}
        {beat.notPassedOn && (
          <p data-not-passed="true" className="hatch-quiet mt-2 flex items-center gap-1.5 rounded-xl px-3 py-2 text-sm font-bold text-bad">
            <span className="flex items-center gap-1.5 rounded-md bg-surface px-1.5 py-0.5"><CrossIcon /> Not passed on · The farmer never hears this line</span>
          </p>
        )}
      </div>
    </div>
  )
}

/** A line for one side only: the other half of the row shows that nothing reaches the other side. */
function Private({ beat, stage, buyerOn }: { beat: Beat; stage: Stage; buyerOn: boolean }) {
  const clip = beat.clips[0]
  const toBuyer = clip.to === 'buyer'
  const readBack = beat.whisperKind === 'read_back'
  const box = readBack ? 'border-assistant/40 bg-assistant-soft' : 'border-2 border-dashed border-whisper-edge bg-whisper-soft'
  const text = readBack ? 'text-assistant' : 'text-whisper'
  const other = !toBuyer && !buyerOn ? 'The buyer is not on the call yet' : `The ${toBuyer ? 'farmer' : 'buyer'} hears nothing`
  const ring = stage.current ? (readBack ? 'ring-2 ring-assistant' : 'ring-2 ring-whisper-edge') : ''
  return (
    <div className="animate-rise grid items-stretch gap-3 lg:grid-cols-2">
      <div className={`flex gap-3 ${toBuyer ? 'flex-row-reverse pl-8 lg:order-2 lg:pl-8' : 'pr-8 lg:pr-8'}`}>
        <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-full text-base ${readBack ? 'bg-assistant-soft text-assistant' : 'bg-whisper-soft text-whisper'}`}><LockIcon /></span>
        <div data-beat={beat.id} className={`min-w-0 rounded-2xl border px-4 py-3 transition-shadow duration-200 ${toBuyer ? 'rounded-tr-md' : 'rounded-tl-md'} ${box} ${ring}`}>
          <p className={`text-sm font-bold ${text}`}>Only the {toBuyer ? 'buyer' : 'farmer'} hears this · {KIND_LABEL[beat.whisperKind ?? 'consent']} · {clip.languages.join(' · ')}</p>
          <Line clip={clip} big />
        </div>
      </div>
      <div className={`hatch-quiet hidden items-center justify-center rounded-2xl text-sm font-semibold text-faint lg:flex ${toBuyer ? 'lg:order-1 lg:mr-8' : 'lg:ml-8'}`}>
        <span className="flex items-center gap-1.5 rounded-md bg-surface px-2 py-0.5"><EarOffIcon /> {other}</span>
      </div>
    </div>
  )
}

function Said({ beat, stage }: { beat: Beat; stage: Stage }) {
  const both = beat.clips.length > 1
  const ring = stage.current ? 'ring-2 ring-assistant' : ''
  return (
    <div className="animate-rise relative z-10 mx-auto w-full max-w-[34rem]">
      <div data-beat={beat.id} className={`rounded-2xl border border-assistant/30 bg-assistant-soft px-4 py-3 text-center transition-shadow duration-200 ${ring}`}>
        {!both && <p className="text-sm font-bold text-assistant">G-One to {beat.clips[0].to} · {beat.clips[0].languages.join(' · ')}</p>}
        {beat.clips.map((clip, i) => (
          <div key={clip.id} data-clip={clip.id} className={`${i > 0 ? 'mt-2 border-t border-ink/10 pt-2' : ''} transition-opacity duration-300 ${stage.isRevealed(i) ? 'opacity-100' : 'opacity-30'}`}>
            {both && <p className="text-sm font-bold text-assistant">G-One to {clip.to} · {clip.languages.join(' · ')}</p>}
            <Line clip={clip} big />
          </div>
        ))}
      </div>
    </div>
  )
}

function Row({ beat, stage, buyerOn }: { beat: Beat; stage: Stage; buyerOn: boolean }) {
  if (beat.speakerRole !== 'ai') return <Turn beat={beat} stage={stage} />
  if (beat.whisperKind) return <Private beat={beat} stage={stage} buyerOn={buyerOn} />
  return <Said beat={beat} stage={stage} />
}

export default function VoicedTimeline({ manifest, state, onReplayPhones }: { manifest: Manifest; state: VoicedState; onReplayPhones: () => void }) {
  const { cursor, finished } = state
  const beats = manifest.beats.slice(0, cursor.beat + 1)
  const { ref, onScroll, away, jump } = useStickToBottom(cursor.beat * 10 + cursor.clip + (finished ? 5 : 0))
  let buyerOn = false
  return (
    <div className="relative min-h-0 flex-1">
      <div ref={ref} onScroll={onScroll} tabIndex={0} role="log" aria-label="Call conversation" className="absolute inset-0 overflow-y-auto overscroll-contain px-3 py-4 outline-none sm:px-5">
        <div className="relative min-h-full space-y-3.5">
          <span aria-hidden="true" className="absolute inset-y-0 left-1/2 hidden w-px bg-line lg:block" />
          {beats.map((beat, index) => {
            buyerOn ||= beat.buyerJoins
            const current = index === cursor.beat && !finished
            const stage: Stage = { current, clip: current ? cursor.clip : -1, isRevealed: (clip) => index < cursor.beat || finished || clip <= cursor.clip }
            const tool = beat.tool ? toolNote({ key: '', tool: beat.tool.tool, args: beat.tool.args, ok: true, result: beat.tool.result, durationMs: 0, atMs: 0 }) : null
            return (
              <div key={beat.id} className="space-y-3.5">
                {beat.id === 'greet' && <Note row={{ tone: 'plain', icon: <MemoryIcon />, text: `Returning caller. Last call: ${manifest.memoryBefore.gist.summary}` }} />}
                {tool && <Note row={{ tone: 'plain', icon: <SearchIcon />, text: tool.text }} />}
                {beat.buyerJoins && <Note row={{ tone: 'ok', icon: <StoreIcon />, text: 'Buyer joined on a second line' }} />}
                <div className={`transition-opacity duration-300 ${current || finished ? '' : 'opacity-70'}`}>
                  <Row beat={beat} stage={stage} buyerOn={buyerOn} />
                </div>
                {beat.note && <Note row={{ tone: 'ok', icon: <CheckIcon />, text: beat.note }} />}
              </div>
            )
          })}
          {finished && <Note row={{ tone: 'ok', icon: <MemoryIcon />, text: 'Call ended. G-One saved a memory note for next time.' }} />}
          {finished && <div className="relative z-10 flex justify-center"><button type="button" className="btn-quiet btn-sm" onClick={onReplayPhones}><RestartIcon /> Replay the message scene</button></div>}
        </div>
      </div>
      {away && <button type="button" onClick={jump} className="btn-primary btn-sm absolute bottom-3 left-1/2 z-20 -translate-x-1/2 shadow-lift">Jump to latest</button>}
    </div>
  )
}
