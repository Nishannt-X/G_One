import { useEffect, useState, type ReactNode } from 'react'
import { CheckIcon, LockIcon, Logo, PauseIcon, PlayIcon, StoreIcon, WheatIcon } from '../ui/icons'

type Who = 'farmer' | 'buyer' | 'whisper' | 'deal'

interface Beat {
  who: Who
  title: string
  original?: { lang: string; text: string }
  translated?: { lang: string; text: string }
}

const BEATS: Beat[] = [
  {
    who: 'farmer',
    title: 'Farmer speaks Kannada',
    original: { lang: 'kn', text: 'ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ ಇದೆ, ಮಾರಬೇಕು.' },
    translated: { lang: 'en', text: 'I have forty quintals of onion to sell.' },
  },
  {
    who: 'buyer',
    title: 'Buyer answers in Bengali',
    original: { lang: 'bn', text: 'কুইন্টাল প্রতি উনিশশো টাকা দেব।' },
    translated: { lang: 'en', text: 'I will give ₹1,900 a quintal.' },
  },
  {
    who: 'whisper',
    title: 'Only the farmer hears this',
    original: { lang: 'kn', text: 'ಸ್ಯಾಂಪಲ್ ರೆಫರೆನ್ಸ್ ಪ್ರಕಾರ ಒಂದು ಸಾವಿರದ ಎಂಟು ನೂರು ರೂಪಾಯಿ ಇತ್ತು.' },
    translated: { lang: 'en', text: 'The dated sample reference was about ₹1,800 a quintal.' },
  },
  { who: 'deal', title: 'Read back, then two yeses' },
]

const BEAT_MS = 4200

function reducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function Bars({ active, tone }: { active: boolean; tone: string }) {
  return (
    <span aria-hidden="true" className={`flex h-5 items-center gap-[3px] ${tone}`}>
      {[0.5, 0.9, 0.4, 1, 0.65].map((h, i) => (
        <span key={i} className={`block h-full w-[3px] origin-center rounded-full bg-current transition-opacity duration-300 ${active ? 'animate-eq opacity-100' : 'opacity-30'}`} style={active ? { height: `${h * 100}%`, animationDelay: `${i * 110}ms` } : { transform: 'scaleY(0.2)' }} />
      ))}
    </span>
  )
}

function Party({ icon, name, lang, active, muted, tone }: { icon: ReactNode; name: string; lang: string; active: boolean; muted?: boolean; tone: string }) {
  return (
    <div className={`flex flex-col items-center gap-1.5 transition-opacity duration-300 ${muted ? 'opacity-40' : ''}`}>
      <span className={`relative grid h-13 w-13 place-items-center rounded-full bg-night-raised text-2xl sm:h-16 sm:w-16 sm:text-3xl ${tone}`}>
        {active && <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-current" />}
        {icon}
      </span>
      <span className="text-sm leading-tight font-bold text-on-night">{name}</span>
      <span className="text-xs leading-tight text-on-night-muted">{lang}</span>
    </div>
  )
}

/** The dashed wire between two parties, flowing the way the words are going. */
function Wire({ state }: { state: 'right' | 'left' | 'idle' | 'cut' }) {
  const flow = state === 'right' ? 'animate-flow' : state === 'left' ? 'animate-flow-back' : ''
  return (
    <svg aria-hidden="true" viewBox="0 0 100 8" preserveAspectRatio="none" className="mt-6 h-2 min-w-4 flex-1 sm:mt-8">
      <line x1="0" y1="4" x2="100" y2="4" vectorEffect="non-scaling-stroke" strokeWidth="2" strokeLinecap="round" strokeDasharray={state === 'cut' ? '1 7' : '6 4'} className={`${flow} transition-[stroke] duration-300`} style={{ stroke: state === 'idle' || state === 'cut' ? 'var(--night-line)' : 'var(--accent)' }} />
    </svg>
  )
}

function Stage({ beat }: { beat: Beat }) {
  if (beat.who === 'deal') {
    return (
      <div className="flex h-full flex-col justify-center">
        <p className="text-sm font-bold tracking-wide text-accent">{beat.title}</p>
        <p className="mt-2 text-2xl leading-tight font-semibold text-on-night sm:text-[1.75rem]">40 quintal onion at ₹1,900 a quintal, pay on delivery.</p>
        <div className="mt-3 flex flex-wrap gap-2">
          <span className="chip bg-farmer-soft text-farmer"><CheckIcon /> Farmer said yes</span>
          <span className="chip bg-buyer-soft text-buyer"><CheckIcon /> Buyer said yes</span>
        </div>
      </div>
    )
  }
  const whisper = beat.who === 'whisper'
  return (
    <div className={`flex h-full flex-col justify-center ${whisper ? 'rounded-xl border border-dashed border-accent/70 bg-accent/10 px-4' : ''}`}>
      <p className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-accent">
        {whisper && <LockIcon />} {beat.title}
      </p>
      <p lang={beat.original?.lang} className="mt-1.5 text-2xl leading-snug font-semibold text-on-night sm:text-[1.75rem]">{beat.original?.text}</p>
      <p lang={beat.translated?.lang} className="mt-1 text-base text-on-night-muted sm:text-lg">{beat.translated?.text}</p>
    </div>
  )
}

/** An illustrative call on a loop. It always takes the same space, and a viewer can stop it or step through it. */
export default function CallScene() {
  const [step, setStep] = useState(0)
  const [running, setRunning] = useState(() => !reducedMotion())

  useEffect(() => {
    if (!running) return
    const timer = setInterval(() => setStep((s) => (s + 1) % BEATS.length), BEAT_MS)
    return () => clearInterval(timer)
  }, [running])

  const beat = BEATS[step]
  const leftWire = beat.who === 'farmer' ? 'right' : beat.who === 'buyer' || beat.who === 'whisper' ? 'left' : 'idle'
  const rightWire = beat.who === 'buyer' ? 'left' : beat.who === 'farmer' ? 'right' : beat.who === 'whisper' ? 'cut' : 'idle'

  return (
    <figure className="on-night relative overflow-hidden rounded-3xl bg-night p-5 text-on-night shadow-lift sm:p-7" aria-label="Illustrative call between a farmer and a buyer with G-One in the middle">
      <div className="flex items-center justify-between gap-3">
        <span className="flex items-center gap-2 text-sm font-semibold text-on-night-muted">
          <span aria-hidden="true" className="h-2 w-2 rounded-full bg-accent" /> Illustrative call
        </span>
        <button type="button" className="btn-night btn-sm w-9 px-0" aria-label={running ? 'Pause the animation' : 'Play the animation'} onClick={() => setRunning((r) => !r)}>
          {running ? <PauseIcon /> : <PlayIcon />}
        </button>
      </div>

      <div className="mt-4 flex items-start gap-2 sm:gap-3">
        <Party icon={<WheatIcon />} name="Farmer" lang="ಕನ್ನಡ" active={beat.who === 'farmer' || beat.who === 'whisper'} tone="text-on-night-farmer" />
        <Wire state={leftWire} />
        <Party icon={<Logo className="text-[1.05em] text-on-night-muted" />} name="G-One" lang="in the middle" active={beat.who === 'whisper' || beat.who === 'deal'} tone="text-accent" />
        <Wire state={rightWire} />
        <Party icon={<StoreIcon />} name="Buyer" lang="বাংলা" active={beat.who === 'buyer'} muted={beat.who === 'whisper'} tone="text-on-night-buyer" />
      </div>

      <div className="mt-4 flex h-5 items-center justify-center gap-6">
        <Bars active={beat.who !== 'deal'} tone="text-accent" />
      </div>

      <div className="relative mt-3 h-[12.5rem] sm:h-[11.5rem]" aria-live="off">
        <div key={step} className="animate-rise h-full">
          <Stage beat={beat} />
        </div>
      </div>

      <div className="mt-3 flex items-center justify-center gap-1">
        {BEATS.map((b, i) => (
          <button key={b.who} type="button" aria-label={`Show step ${i + 1}: ${b.title}`} aria-current={i === step ? 'step' : undefined} className="grid h-6 w-8 cursor-pointer place-items-center rounded" onClick={() => { setRunning(false); setStep(i) }}>
            <span className={`block h-1.5 rounded-full transition-all duration-300 ${i === step ? 'w-6 bg-accent' : 'w-2.5 bg-night-line'}`} />
          </button>
        ))}
      </div>
    </figure>
  )
}
