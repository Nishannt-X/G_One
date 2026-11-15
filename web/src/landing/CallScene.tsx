import type { ReactNode } from 'react'
import { CheckIcon, LockIcon, Logo, StoreIcon, WheatIcon } from '../ui/icons'

type Wire = 'right' | 'left' | 'idle' | 'cut'

/** One pane of the illustrative call. Pane `i` illustrates point `i` of "One call. Five things happen." beside it. */
interface Beat {
  title: string
  /** Who is speaking or being spoken to, which lights their ring. */
  lit: { farmer?: boolean; gone?: boolean; buyer?: boolean }
  /** The buyer is not on the line (yet), or cannot hear this. */
  buyerAway?: boolean
  wires: [Wire, Wire]
  body: (sz: Sized) => ReactNode
  /**
   * The mini scene's pane: the label and the main line of the full pane, word for word. A main line in Kannada or
   * Bengali also carries `gloss`, the full pane's English line under it, so a visitor can follow it and sees the
   * translation happen.
   */
  brief: { label: string; text: string; lang: string; gloss?: string }
}

/**
 * Builds a class list from the phone-size classes plus the larger `sm:` ones. A compact scene (a short window) keeps the
 * phone sizes at every width, so the whole stage still fits in view beside its point.
 */
type Sized = (base: string, wide: string) => string

const type = (sz: Sized) => ({
  label: sz('flex items-center gap-1.5 text-xs font-bold tracking-wide text-accent', 'sm:text-sm'),
  big: sz('mt-1 text-lg leading-snug font-semibold text-on-night', 'sm:mt-1.5 sm:text-[1.75rem]'),
  sub: sz('mt-1 text-sm text-on-night-muted', 'sm:text-lg'),
  gap: sz('mt-2', 'sm:mt-3'),
  small: sz('mt-1.5 text-xs text-on-night-muted', 'sm:mt-2 sm:text-sm'),
})

const BEATS: Beat[] = [
  {
    title: 'The farmer calls',
    lit: { farmer: true, gone: true },
    buyerAway: true,
    wires: ['right', 'cut'],
    brief: { label: 'The farmer calls G-One', text: 'Ringing…', lang: 'en' },
    body: (sz) => {
      const { label, big, sub } = type(sz)
      return (
        <>
          <p className={label}>The farmer calls G-One</p>
          <p className={big}>Ringing…</p>
          <p className={sub}>No app and no sign-up. The buyer is not on the line yet.</p>
        </>
      )
    },
  },
  {
    title: 'G-One knows them',
    lit: { farmer: true, gone: true },
    buyerAway: true,
    wires: ['left', 'cut'],
    brief: { label: 'G-One remembers the last call', text: 'ನಮಸ್ಕಾರ ಬಸವರಾಜ್ ಅವರೇ! ಹೋದ ಸಲ ಈರುಳ್ಳಿ rate ಕೇಳಿದ್ರಿ, ನೆನಪಿದೆ.', lang: 'kn', gloss: 'Namaskara, Basavaraj! Last time you asked about the onion rate, I remember.' },
    body: (sz) => {
      const { label, big, sub } = type(sz)
      return (
        <>
          <p className={label}>G-One remembers the last call</p>
          <p lang="kn" className={big}>ನಮಸ್ಕಾರ ಬಸವರಾಜ್ ಅವರೇ! ಹೋದ ಸಲ ಈರುಳ್ಳಿ rate ಕೇಳಿದ್ರಿ, ನೆನಪಿದೆ.</p>
          <p lang="en" className={sub}>Namaskara, Basavaraj! Last time you asked about the onion rate, I remember.</p>
        </>
      )
    },
  },
  {
    title: 'It asks before it connects',
    lit: { gone: true, buyer: true },
    wires: ['idle', 'right'],
    brief: { label: 'G-One asks the buyer first, on a second line', text: '“Is now a good time? Please say yes or no.”', lang: 'en' },
    body: (sz) => {
      const { label, big, gap, small } = type(sz)
      return (
        <>
          <p className={label}>G-One asks the buyer first, on a second line</p>
          <p className={big}>“Is now a good time? Please say yes or no.”</p>
          <div className={`${gap} flex flex-wrap gap-2`}>
            <span className="chip bg-farmer-soft text-farmer"><CheckIcon /> Farmer: yes, connect</span>
            <span className="chip bg-buyer-soft text-buyer"><CheckIcon /> Buyer: yes, I'll talk</span>
          </div>
          <p className={small}>A yes to joining the call, not to any price.</p>
        </>
      )
    },
  },
  {
    title: 'It translates and advises',
    lit: { farmer: true, gone: true, buyer: true },
    wires: ['left', 'left'],
    brief: { label: 'The buyer speaks Bengali, the farmer hears Kannada', text: 'কুইন্টাল প্রতি উনিশশো টাকা দেব।', lang: 'bn', gloss: 'I will give ₹1,900 a quintal.' },
    body: (sz) => {
      const { label, gap } = type(sz)
      return (
        <>
          <p className={label}>The buyer speaks Bengali, the farmer hears Kannada</p>
          <p lang="bn" className={sz('mt-1 text-lg leading-snug font-semibold text-on-night', 'sm:text-2xl')}>কুইন্টাল প্রতি উনিশশো টাকা দেব।</p>
          <p lang="en" className={sz('text-sm text-on-night-muted', 'sm:text-base')}>I will give ₹1,900 a quintal.</p>
          <div className={`${gap} rounded-xl border border-dashed border-accent/70 bg-accent/10 px-3 py-2`}>
            <p className={sz('flex items-center gap-1.5 text-xs font-bold text-accent', 'sm:text-sm')}><LockIcon /> Only the farmer hears this</p>
            <p lang="en" className={sz('mt-0.5 text-sm text-on-night', 'sm:text-base')}>The dated sample reference was about ₹1,800 a quintal.</p>
          </div>
        </>
      )
    },
  },
  {
    title: 'Both agree to the terms',
    lit: { farmer: true, gone: true, buyer: true },
    wires: ['idle', 'idle'],
    brief: { label: 'Read back to both, then a yes from each', text: '40 quintal onion at ₹1,900 a quintal, pay on delivery.', lang: 'en' },
    body: (sz) => {
      const { label, gap, small } = type(sz)
      return (
        <>
          <p className={label}>Read back to both, then a yes from each</p>
          <p className={sz('mt-1.5 text-xl leading-tight font-semibold text-on-night', 'sm:mt-2 sm:text-[1.75rem]')}>40 quintal onion at ₹1,900 a quintal, pay on delivery.</p>
          <div className={`${gap} flex flex-wrap gap-2`}>
            <span className="chip bg-farmer-soft text-farmer"><CheckIcon /> Farmer said yes</span>
            <span className="chip bg-buyer-soft text-buyer"><CheckIcon /> Buyer said yes</span>
          </div>
          <p className={small}>A record of the call, not a contract.</p>
        </>
      )
    },
  },
]

function Bars({ active, tone }: { active: boolean; tone: string }) {
  return (
    <span aria-hidden="true" className={`flex h-5 items-center gap-[3px] ${tone}`}>
      {[0.5, 0.9, 0.4, 1, 0.65].map((h, i) => (
        <span key={i} className={`block h-full w-[3px] origin-center rounded-full bg-current transition-opacity duration-300 ${active ? 'animate-eq opacity-100' : 'opacity-30'}`} style={active ? { height: `${h * 100}%`, animationDelay: `${i * 110}ms` } : { transform: 'scaleY(0.2)' }} />
      ))}
    </span>
  )
}

/**
 * How big the scene is drawn. `roomy` is the full design. `compact` (short windows) keeps phone-size type and spacing at
 * every width, with no language lines and no sound bars, so the scene fits in view beside its point; the words in each
 * pane stay the same. `mini` (very short windows) is a short card that can stay docked under the points: small parties
 * and, in each pane, only its label and main line, plus the English line under a Kannada or Bengali one. It lays itself
 * out side by side once the card is wide enough.
 */
export type SceneSize = 'roomy' | 'compact' | 'mini'

function Party({ icon, name, lang, active, muted, tone, size }: { icon: ReactNode; name: string; lang: string; active: boolean; muted?: boolean; tone: string; size: SceneSize }) {
  const ring = size === 'mini' ? 'h-8 w-8 text-base' : size === 'compact' ? 'h-11 w-11 text-xl' : 'h-11 w-11 text-xl sm:h-16 sm:w-16 sm:text-3xl'
  return (
    <div className={`flex flex-col items-center transition-opacity duration-300 ${size === 'mini' ? 'gap-1' : 'gap-1.5'} ${muted ? 'opacity-40' : ''}`}>
      <span className={`relative grid place-items-center rounded-full bg-night-raised ${ring} ${tone}`}>
        {active && <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-current" />}
        {icon}
      </span>
      <span className={`leading-tight font-bold text-on-night ${size === 'mini' ? 'text-xs' : 'text-sm'}`}>{name}</span>
      {/* Smaller scenes drop the language line; the pane labels still name both languages. */}
      {size === 'roomy' && <span className="text-xs leading-tight text-on-night-muted">{lang}</span>}
    </div>
  )
}

/** The dashed wire between two parties, flowing the way the words are going. It sits level with the middle of the rings. */
function WireLine({ state, size }: { state: Wire; size: SceneSize }) {
  const flow = state === 'right' ? 'animate-flow' : state === 'left' ? 'animate-flow-back' : ''
  const level = size === 'mini' ? 'mt-3' : size === 'compact' ? 'mt-5' : 'mt-5 sm:mt-8'
  return (
    <svg aria-hidden="true" viewBox="0 0 100 8" preserveAspectRatio="none" className={`h-2 min-w-4 flex-1 ${level}`}>
      <line x1="0" y1="4" x2="100" y2="4" vectorEffect="non-scaling-stroke" strokeWidth="2" strokeLinecap="round" strokeDasharray={state === 'cut' ? '1 7' : '6 4'} className={`${flow} transition-[stroke] duration-300`} style={{ stroke: state === 'idle' || state === 'cut' ? 'var(--night-line)' : 'var(--accent)' }} />
    </svg>
  )
}

function Parties({ beat, size, className }: { beat: Beat; size: SceneSize; className: string }) {
  return (
    <div className={`flex items-start ${className}`}>
      <Party icon={<WheatIcon />} name="Farmer" lang="ಕನ್ನಡ" active={!!beat.lit.farmer} tone="text-on-night-farmer" size={size} />
      <WireLine state={beat.wires[0]} size={size} />
      <Party icon={<Logo className="text-[1.05em] text-on-night-muted" />} name="G-One" lang="in the middle" active={!!beat.lit.gone} tone="text-accent" size={size} />
      <WireLine state={beat.wires[1]} size={size} />
      <Party icon={<StoreIcon />} name="Buyer" lang="বাংলা" active={!!beat.lit.buyer} muted={beat.buyerAway} tone="text-on-night-buyer" size={size} />
    </div>
  )
}

function Badge({ small = false }: { small?: boolean }) {
  return (
    <p className={`flex items-center gap-2 font-semibold text-on-night-muted ${small ? 'text-xs' : 'text-sm'}`}>
      <span aria-hidden="true" className="h-2 w-2 rounded-full bg-accent" /> Illustrative call
    </p>
  )
}

/**
 * Every pane shares one grid cell, so the card keeps the height of its tallest pane and never jumps. Panes other than
 * the current one are hidden, which also removes them from the accessibility tree; the points beside the scene carry
 * the same story as text. A pane before the current one is marked `is-before` and one after it `is-after`, so the
 * change animates the way the visitor scrolled.
 */
function Panes({ current, className, pane }: { current: number; className: string; pane: (b: Beat) => ReactNode }) {
  return (
    <div className={`grid ${className}`}>
      {BEATS.map((b, i) => (
        <div key={b.title} data-pane={i} aria-hidden={i === current ? undefined : true} className={`call-pane col-start-1 row-start-1 flex flex-col justify-center ${i === current ? 'is-current' : i < current ? 'is-before' : 'is-after'}`}>
          {pane(b)}
        </div>
      ))}
    </div>
  )
}

const LABEL = 'Illustrative call between a farmer and a buyer with G-One in the middle'

/**
 * An illustrative call, one pane per point beside it. It never plays by itself and has no controls of its own: the page
 * drives `step` from the visitor's scrolling.
 */
export default function CallScene({ step, size = 'roomy' }: { step: number; size?: SceneSize }) {
  const current = Math.min(Math.max(step, 0), BEATS.length - 1)
  const beat = BEATS[current]

  if (size === 'mini') {
    // A container query, not a window one: the card is as wide as the column it is docked in.
    return (
      <figure data-size="mini" className="on-night @container relative overflow-hidden rounded-2xl bg-night p-2.5 text-on-night shadow-lift" aria-label={LABEL}>
        <div className="grid grid-cols-[minmax(0,1fr)] gap-2 @min-[26rem]:grid-cols-[11rem_minmax(0,1fr)] @min-[26rem]:items-center @min-[26rem]:gap-3">
          <div>
            <Badge small />
            <Parties beat={beat} size={size} className="mt-1 gap-1.5" />
          </div>
          <Panes
            current={current}
            className=""
            pane={(b) => (
              <>
                <p className="text-xs font-bold tracking-wide text-accent">{b.brief.label}</p>
                <p lang={b.brief.lang} className="mt-0.5 text-sm leading-snug font-semibold text-on-night">{b.brief.text}</p>
                {b.brief.gloss && <p lang="en" className="mt-0.5 text-xs leading-snug text-on-night-muted">{b.brief.gloss}</p>}
              </>
            )}
          />
        </div>
      </figure>
    )
  }

  const compact = size === 'compact'
  const sz: Sized = (base, wide) => (compact ? base : `${base} ${wide}`)
  return (
    <figure data-size={size} className={`on-night relative overflow-hidden rounded-3xl bg-night text-on-night shadow-lift ${compact ? 'p-3' : 'p-4 sm:p-7'}`} aria-label={LABEL}>
      <Badge />
      <Parties beat={beat} size={size} className={compact ? 'mt-2 gap-2' : 'mt-3 gap-2 sm:mt-4 sm:gap-3'} />

      {/* The sound bars are decoration; narrow screens and smaller scenes drop them so the whole stage fits the window. */}
      {!compact && (
        <div className="mt-4 hidden h-5 items-center justify-center sm:flex">
          <Bars active={current !== BEATS.length - 1} tone="text-accent" />
        </div>
      )}

      <Panes current={current} className={compact ? 'mt-2' : 'mt-3'} pane={(b) => b.body(sz)} />
    </figure>
  )
}
