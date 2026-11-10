import type { ReactNode } from 'react'
import type { CallView } from '../../lib/callState'
import { rupees } from '../../lib/format'
import { meterPosition, METER_RANGE_PCT, priceCheck } from '../../lib/pricing'
import type { LegRole } from '../../types/console'
import { CheckIcon, CrossIcon, EarOffIcon, LockIcon, Logo, PlayIcon, RestartIcon, StoreIcon, WheatIcon } from '../../ui/icons'
import { Waveform } from '../parts'
import type { VoicedEngine, VoicedState } from './engine'
import type { Beat, BeatKind, Clip, Manifest } from './manifest'
import { Phone } from './PhoneMock'
import { currentClip, langOf, phoneLit, sideNow } from './sides'

/** Each language written in its own script, for the chips and the travelling speech bubbles. */
const SCRIPT: Record<string, string> = { kn: 'ಕನ್ನಡ', bn: 'বাংলা', hi: 'हिंदी', en: 'English' }
const NAME: Record<string, string> = { kn: 'Kannada', bn: 'Bengali', hi: 'Hindi', en: 'English' }
const KIND_LABEL: Record<BeatKind, string> = { consent: 'Consent', price_reference: 'Price note', caution: 'Caution', read_back: 'Read-back' }

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

/** Which wire the words travel along, and which way: into G-One from a speaker, or out of G-One to a listener. */
function flowOf(beat: Beat | null, clip: Clip | null): { side: LegRole; inward: boolean } | null {
  if (!beat || !clip) return null
  if (clip.kind === 'original') return beat.speakerRole === 'ai' ? null : { side: beat.speakerRole, inward: true }
  return clip.to ? { side: clip.to, inward: false } : null
}

const TONE = {
  farmer: { text: 'text-on-night-farmer', ring: 'ring-on-night-farmer', chip: 'border-on-night-farmer/40 text-on-night-farmer' },
  buyer: { text: 'text-on-night-buyer', ring: 'ring-on-night-buyer', chip: 'border-on-night-buyer/40 text-on-night-buyer' },
}

function Party({ party, manifest, state }: { party: LegRole; manifest: Manifest; state: VoicedState }) {
  const now = sideNow(manifest, state.cursor, party, state.finished)
  const active = state.cursor.voicing && (now.hearing === 'speaking' || now.hearing === 'hears')
  const silent = now.hearing === 'silent'
  const lang = now.clip && !silent ? langOf(now.clip.language) : langOf(manifest[party].language)
  const tone = TONE[party]
  return (
    <div data-party={party} data-hearing={now.hearing ?? 'idle'} className="flex w-[5.5rem] shrink-0 flex-col items-center gap-2 text-center sm:w-36">
      <span className={`relative grid h-14 w-14 place-items-center rounded-full bg-night-raised text-2xl transition-[opacity,transform] duration-500 sm:h-20 sm:w-20 sm:text-3xl ${tone.text} ${silent ? 'scale-90 opacity-45' : ''} ${active ? `ring-2 ${tone.ring}` : 'ring-1 ring-night-line'}`}>
        {active && <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-current" />}
        {party === 'farmer' ? <WheatIcon /> : <StoreIcon />}
      </span>
      <span className="max-w-full truncate text-sm font-bold text-on-night sm:text-base">{manifest[party].name}</span>
      <Waveform active={active && !silent} className={`h-4 ${tone.text}`} />
      {silent ? (
        <span className="inline-flex h-7 items-center gap-1 rounded-full border border-dashed border-night-line px-2 text-xs font-semibold text-on-night-muted"><EarOffIcon /> <span className="hidden sm:inline">Hears nothing</span><span className="sm:hidden">Silent</span></span>
      ) : (
        <span key={lang} lang={lang} className={`animate-rise inline-flex h-7 items-center rounded-full border px-2.5 text-sm font-semibold ${tone.chip}`}>{SCRIPT[lang] ?? lang}</span>
      )}
    </div>
  )
}

/** The line between a person and G-One. A speech bubble in the line's own script travels along it while a voice plays. */
function Wire({ side, flow, clip, voicing }: { side: LegRole; flow: ReturnType<typeof flowOf>; clip: Clip | null; voicing: boolean }) {
  const on = flow?.side === side && voicing && clip
  // Left wire runs farmer -> G-One; right wire runs G-One -> buyer. Inward on the right wire travels leftward.
  const rightward = on ? (side === 'farmer') === flow.inward : false
  const lang = clip ? langOf(clip.language) : 'en'
  const bubble = clip?.kind === 'original' ? (side === 'farmer' ? 'bg-on-night-farmer text-night' : 'bg-on-night-buyer text-night') : 'bg-accent text-on-accent'
  return (
    <div aria-hidden="true" className="relative mt-7 h-8 min-w-6 flex-1 sm:mt-10">
      <span className={`absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 rounded-full transition-opacity duration-300 ${on ? 'bg-accent/70 opacity-100' : 'bg-night-line opacity-100'}`} />
      {on && (
        <div key={clip.id} className={`cinema-travel absolute inset-0 ${rightward ? '' : 'cinema-travel-back'}`}>
          <span lang={lang} className={`absolute top-1/2 left-0 inline-flex -translate-x-1/2 -translate-y-1/2 items-center rounded-full px-2.5 py-0.5 text-xs font-bold whitespace-nowrap shadow-lift sm:text-sm ${bubble}`}>{SCRIPT[lang] ?? lang}</span>
        </div>
      )}
    </div>
  )
}

function Middle({ manifest, state }: { manifest: Manifest; state: VoicedState }) {
  const { beat, clip } = currentClip(manifest, state.cursor)
  const busy = state.cursor.voicing && clip !== null && clip.kind !== 'original'
  return (
    <div className="flex w-[6.5rem] shrink-0 flex-col items-center gap-2 text-center sm:w-48">
      <span className={`relative grid h-16 w-16 place-items-center rounded-full bg-night-raised text-3xl transition-shadow duration-500 sm:h-24 sm:w-24 sm:text-4xl ${busy ? 'shadow-[0_0_0_6px_color-mix(in_srgb,var(--accent)_25%,transparent),0_0_48px_-6px_var(--accent)]' : 'ring-1 ring-night-line'}`}>
        <Logo className="text-[1.1em] text-on-night-muted" />
      </span>
      <span className="text-sm font-bold text-on-night sm:text-base">G-One</span>
      <span aria-live="polite" className="line-clamp-2 min-h-[2.5em] text-xs leading-tight text-on-night-muted sm:text-sm">{phaseText(beat, clip, state.finished)}</span>
    </div>
  )
}

function Subtitle({ manifest, state }: { manifest: Manifest; state: VoicedState }) {
  const { beat, clip } = currentClip(manifest, state.cursor)
  if (!beat || !clip) return null
  const lang = langOf(clip.language)
  const languages = clip.languages.join(' · ') || NAME[lang]
  const whisper = beat.speakerRole === 'ai' && beat.whisperKind !== null && beat.whisperKind !== 'read_back'
  const readBack = beat.whisperKind === 'read_back'
  const speaker = beat.speakerRole === 'ai' ? null : manifest[beat.speakerRole].name
  const label =
    clip.kind === 'original' ? `${speaker} says · ${languages}` :
    whisper ? `Only the ${clip.to} hears this · ${KIND_LABEL[beat.whisperKind!]} · ${languages}` :
    readBack ? `G-One reads the deal back to the ${clip.to} · ${languages}` :
    clip.kind === 'heard' ? `The ${clip.to} hears, translated · ${languages}` : `G-One to the ${clip.to} · ${languages}`
  // Long lines step down a size, so the stage keeps its controls in view.
  const long = clip.text.length > 110
  return (
    <div key={clip.id} data-clip={clip.id} className={`animate-rise relative mx-auto w-full max-w-4xl rounded-3xl px-5 py-5 text-center sm:px-8 sm:py-6 ${whisper ? 'cinema-whisper border-2 border-dashed border-whisper-edge bg-whisper-edge/10' : ''}`}>
      <p className={`flex flex-wrap items-center justify-center gap-1.5 text-sm font-bold tracking-wide sm:text-base ${whisper ? 'text-accent' : 'text-on-night-muted'}`}>{whisper && <LockIcon />}{label}</p>
      <p lang={lang} className={`mt-3 leading-snug font-semibold text-on-night ${long ? 'text-[clamp(1.25rem,0.95rem+1vw,1.75rem)]' : 'text-[clamp(1.375rem,1rem+1.6vw,2.375rem)]'}`}>{clip.text}</p>
      {clip.gloss && <p lang="en" className={`mt-2 text-base text-on-night-muted ${long ? 'sm:text-lg' : 'sm:text-xl'}`}>{clip.gloss}</p>}
      {beat.notPassedOn && clip.kind === 'original' && (
        <p className="mt-3 inline-flex items-center gap-1.5 rounded-full border border-dashed border-night-line px-3 py-1 text-sm font-semibold text-on-night-muted"><CrossIcon /> Not passed on. The farmer never hears this line.</p>
      )}
    </div>
  )
}

function Yes({ who, yes }: { who: string; yes: boolean }) {
  return (
    <span className={`flex items-center gap-2 text-sm font-semibold ${yes ? 'text-on-night' : 'text-on-night-muted'}`}>
      <span className={`grid h-8 w-8 place-items-center rounded-full ${yes ? 'cinema-pop bg-on-night-farmer text-night' : 'border-2 border-dashed border-night-line'}`}>{yes && <CheckIcon />}</span>
      {who}
    </span>
  )
}

function DealStrip({ view }: { view: CallView | null }) {
  const deal = view?.deal ?? null
  const { comparison, delta, verdict } = priceCheck(view)
  const terms: [string, string | null][] = [
    ['Crop', deal?.commodity ?? null],
    ['Quantity', deal?.quantity ?? null],
    ['Price', deal?.price != null ? `${rupees(deal.price)}${deal.unit ? ` / ${deal.unit}` : ''}` : null],
  ]
  return (
    <div className="grid gap-4 border-t border-night-line px-4 py-4 sm:px-6 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)_auto] lg:items-center lg:gap-8">
      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {terms.map(([label, value]) => (
          <div key={label} className={`min-w-0 rounded-xl bg-night-raised/80 px-3 py-2 ${label === 'Price' ? 'max-sm:col-span-2' : ''}`}>
            <dt className="text-xs font-medium text-on-night-muted">{label}</dt>
            <dd key={value ?? ''} className={`truncate font-semibold ${value ? 'cinema-pop text-on-night' : 'text-on-night-muted'}`}>{value ?? '—'}</dd>
          </div>
        ))}
      </dl>
      <div role="img" aria-label={verdict ? `Price check: ${verdict}` : 'Price check: no offer to compare yet'}>
        <div className="flex justify-between text-xs text-on-night-muted"><span>Price check</span><span className="font-semibold text-on-night">{verdict ?? 'Waiting for an offer'}</span></div>
        <div className="relative mt-2 h-6">
          <div className={`absolute inset-x-0 top-2 h-2 origin-left rounded-full ${comparison ? 'cinema-sweep' : 'opacity-30'}`} style={{ background: 'linear-gradient(90deg, var(--bad), var(--whisper-edge) 42%, var(--on-night-farmer) 62%, var(--on-night-farmer))' }} />
          <span aria-hidden="true" className="absolute top-0.5 left-1/2 h-5 w-0.5 -translate-x-1/2 bg-on-night/70" />
          {comparison && (
            <div aria-hidden="true" className="absolute inset-0 transition-transform duration-700 ease-out" style={{ transform: `translateX(${meterPosition(delta)}%)` }}>
              <span className="cinema-pop absolute top-0 left-0 block h-6 w-6 -translate-x-1/2 rounded-full border-[3px] border-night bg-accent shadow-lift" />
            </div>
          )}
        </div>
        <div className="mt-1 flex justify-between text-[0.6875rem] text-on-night-muted tabular-nums"><span>−{METER_RANGE_PCT}%</span><span>Dated reference</span><span>+{METER_RANGE_PCT}%</span></div>
      </div>
      <div className="flex items-center gap-5">
        <Yes who="Farmer yes" yes={deal?.farmerYes ?? false} />
        <Yes who="Buyer yes" yes={deal?.buyerYes ?? false} />
      </div>
    </div>
  )
}

function StagePhone({ party, manifest, lit }: { party: LegRole; manifest: Manifest; lit: boolean }) {
  return (
    <div className="relative">
      <Phone party={party} manifest={manifest} lit={lit} />
      {lit && <span aria-hidden="true" className="cinema-stamp pointer-events-none absolute top-3 -right-4 z-40 inline-flex items-center gap-1 rounded-lg border-[3px] border-accent bg-night/85 px-2 py-1 text-xs font-extrabold tracking-wider text-accent uppercase">Two yeses</span>}
    </div>
  )
}

/** The end of the sample: the deal summary lands on both phones, either side of the last line. Illustrative only. */
function Finale({ manifest, state, onReplayPhones }: { manifest: Manifest; state: VoicedState; onReplayPhones: () => void }) {
  const farmerLit = phoneLit(manifest, state.cursor, 'farmer', state.finished)
  const buyerLit = phoneLit(manifest, state.cursor, 'buyer', state.finished)
  return (
    <div className="grid w-full items-center gap-5 lg:grid-cols-[auto_minmax(0,1fr)_auto] lg:gap-8">
      <div className="flex flex-col items-center gap-3 lg:order-2">
        {state.finished ? (
          <div className="animate-rise text-center">
            <p className="text-sm font-bold tracking-wide text-accent">The call has ended</p>
            <p className="mt-2 text-[clamp(1.375rem,1rem+1.4vw,2rem)] leading-snug font-semibold">In the sample, both phones show the deal summary.</p>
            <p className="mt-2 text-on-night-muted">Illustrative: in this sample, nothing is sent.</p>
          </div>
        ) : (
          <Subtitle manifest={manifest} state={state} />
        )}
        {state.finished && <button type="button" className="btn-night btn-sm" onClick={onReplayPhones}><RestartIcon /> Replay the message scene</button>}
      </div>
      <div className="cinema-rise-in flex justify-center gap-3 max-sm:[zoom:0.82] sm:gap-10 lg:contents">
        <div className="lg:order-1"><StagePhone party="farmer" manifest={manifest} lit={farmerLit} /></div>
        <div className="lg:order-3"><StagePhone party="buyer" manifest={manifest} lit={buyerLit} /></div>
      </div>
    </div>
  )
}

interface Props {
  manifest: Manifest | null
  state: VoicedState
  engine: VoicedEngine
  view: CallView | null
  intro: ReactNode
}

/** The staged scene for the scripted sample: three parties, the words travelling between them, and the deal taking shape. */
export default function CinemaStage({ manifest, state, engine, view, intro }: Props) {
  const on = state.active && manifest !== null
  const { beat, clip } = manifest ? currentClip(manifest, state.cursor) : { beat: null, clip: null }
  const flow = flowOf(beat, clip)
  const finale = on && phoneLit(manifest, state.cursor, 'farmer', state.finished)
  return (
    <section aria-label="Sample call stage" className="on-night relative isolate overflow-hidden rounded-[1.75rem] bg-night text-on-night shadow-lift">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="dots-night absolute inset-0 [mask-image:radial-gradient(80%_70%_at_50%_30%,black,transparent)]" />
        <div className="absolute -top-40 left-1/2 h-[30rem] w-[44rem] -translate-x-1/2 rounded-full bg-accent/15 blur-3xl" />
        <div className="absolute -bottom-40 -left-20 h-80 w-80 rounded-full bg-on-night-farmer/10 blur-3xl" />
        <div className="absolute -right-20 -bottom-40 h-80 w-80 rounded-full bg-on-night-buyer/10 blur-3xl" />
      </div>
      {on ? (
        <>
          <div className="flex items-start justify-center gap-1 px-3 pt-5 sm:gap-3 sm:px-8 sm:pt-6">
            <Party party="farmer" manifest={manifest} state={state} />
            <Wire side="farmer" flow={flow} clip={clip} voicing={state.cursor.voicing} />
            <Middle manifest={manifest} state={state} />
            <Wire side="buyer" flow={flow} clip={clip} voicing={state.cursor.voicing} />
            <Party party="buyer" manifest={manifest} state={state} />
          </div>
          <div className="flex min-h-[14rem] items-center px-3 py-3 sm:min-h-[15.5rem] sm:px-8">
            {finale ? <Finale manifest={manifest} state={state} onReplayPhones={() => engine.replayPhones()} /> : <Subtitle manifest={manifest} state={state} />}
          </div>
          <DealStrip view={view} />
        </>
      ) : (
        intro
      )}
    </section>
  )
}

/** The stage before the sample plays, or when its voices could not load. */
export function StageIntro({ state, onPlay, onRetry }: { state: VoicedState; onPlay: () => void; onRetry: () => void }) {
  const failed = state.status === 'failed'
  const loading = state.status === 'loading'
  return (
    <div className="flex min-h-[30rem] flex-col items-center justify-center px-5 py-12 text-center sm:min-h-[34rem]">
      <div aria-hidden="true" className="flex items-center gap-3 text-2xl sm:gap-6 sm:text-3xl">
        <span className="grid h-14 w-14 place-items-center rounded-full bg-night-raised text-on-night-farmer ring-1 ring-night-line sm:h-16 sm:w-16"><WheatIcon /></span>
        <span className="h-0.5 w-8 rounded-full bg-accent/60 sm:w-16" />
        <span className="grid h-16 w-16 place-items-center rounded-full bg-night-raised ring-1 ring-night-line sm:h-20 sm:w-20"><Logo className="text-[1.1em] text-on-night-muted" /></span>
        <span className="h-0.5 w-8 rounded-full bg-accent/60 sm:w-16" />
        <span className="grid h-14 w-14 place-items-center rounded-full bg-night-raised text-on-night-buyer ring-1 ring-night-line sm:h-16 sm:w-16"><StoreIcon /></span>
      </div>
      <h2 className="mt-8 max-w-2xl text-3xl leading-tight font-semibold sm:text-5xl">Watch a deal cross two languages.</h2>
      <p className="mt-4 max-w-xl text-lg text-on-night-muted">A farmer speaks Kannada with some English. A buyer speaks Bengali with some Hindi. Hear every line, what each side receives, and what G-One whispers to the farmer alone. Sound on.</p>
      {failed ? (
        <div className="mt-7 flex flex-col items-center gap-3">
          <p role="alert" className="font-semibold text-accent">The voices did not load.</p>
          <button type="button" className="btn-accent btn-lg" onClick={onRetry}><RestartIcon /> Try again</button>
        </div>
      ) : (
        <button type="button" className="btn-accent btn-lg mt-7" disabled={loading} onClick={onPlay}>
          <PlayIcon /> {loading ? `Loading voices ${state.loaded}/${state.total || '…'}` : 'Play the sample call'}
        </button>
      )}
      <p className="mt-4 text-sm text-on-night-muted">Scripted sample, synthetic voices. About three minutes.</p>
    </div>
  )
}
