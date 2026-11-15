import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import CallScene, { type SceneSize } from '../landing/CallScene'
import HeroArt from '../landing/HeroArt'
import { listProgress } from '../landing/listProgress'
import { BUILT_FACTS, type BuiltIcon } from '../landing/builtFacts'
import { IMPACT_STATS } from '../landing/impactStats'
import {
  AlertIcon,
  ArrowIcon,
  ClockIcon,
  DoubleCheckIcon,
  ExternalIcon,
  HandshakeIcon,
  LanguageIcon,
  LockIcon,
  Logo,
  MemoryIcon,
  MicIcon,
  PhoneIcon,
  PlayIcon,
  StoreIcon,
  UserCheckIcon,
  WheatIcon,
} from '../ui/icons'
import Reveal from '../ui/Reveal'

const PROBLEMS: { icon: ReactNode; title: string; body: string }[] = [
  { icon: <ClockIcon />, title: 'The price arrives last', body: "The trader on the other end usually knows the day's rates. The farmer often finds out after saying yes." },
  { icon: <HandshakeIcon />, title: 'Many hands take a share', body: 'Between the field and the final buyer stand agents and commission men, each taking a cut the farmer rarely sees.' },
  { icon: <LanguageIcon />, title: 'The buyer speaks another language', body: 'A better buyer in another state is no use when neither side can follow the other on a phone call.' },
  { icon: <AlertIcon />, title: 'One bad call can cost a season', body: 'Requests for an OTP, an advance, or a sudden price cut arrive by phone, with nobody beside the farmer to say wait.' },
]

/**
 * The points of "One call. Five things happen.", one per pane of the call scene beside them, in the same order.
 * Joining the call (point 3) and agreeing to the deal (point 5) are separate yeses, and the copy keeps them apart.
 */
const STEPS: { title: string; body: string }[] = [
  { title: 'The farmer calls', body: 'Designed for an ordinary phone call, with a browser experience available today. No app and no sign-up.' },
  { title: 'G-One knows them', body: 'If the farmer agreed to be remembered on an earlier call, G-One can greet them by name and pick up from the last call. It answers questions on prices and weather either way.' },
  { title: 'It asks before it connects', body: 'G-One suggests a buyer for the crop and rings them on a second line only after the farmer says yes. The buyer hears who is calling and why, and joins only by saying yes too.' },
  { title: 'It translates and advises', body: 'Each side hears the other in their own language. Notes on price and caution go to the farmer alone; the buyer does not hear them.' },
  { title: 'Both agree to the terms', body: 'Agreeing to talk is not agreeing to a deal. G-One reads the exact terms back to both, and a deal summary is made only when each says yes. It is a record of the call, not a contract.' },
]

const CAPABILITY_ICONS: Partial<Record<BuiltIcon, ReactNode>> = {
  speech: <MicIcon />, translate: <LanguageIcon />, whisper: <LockIcon />,
  consent: <UserCheckIcon />, deal: <DoubleCheckIcon />, memory: <MemoryIcon />,
}

/**
 * Homepage wording for three capabilities, checked against the backend: the buyer is rung only after the farmer's yes
 * and joins only on their own yes (BuyerConsent, the buyerIntro line); a deal summary needs a yes from each side after
 * the read-back (DealDesk). Memory is written only with the farmer's yes and a caller that can be recognised
 * (GistOnHangup); "forget me" deletes it. A withheld number gets no caller-linked memory, though an anonymous
 * operational record of the call can still be kept (CallRecordWriter), so the copy never says "nothing is saved".
 */
const CAPABILITY_COPY: Partial<Record<BuiltIcon, { title: string; body: string }>> = {
  consent: { title: 'Asked before anyone is connected', body: 'G-One rings a matched buyer only after the farmer says yes, and the buyer joins only after hearing who is calling and saying yes too. This yes is to the call, not to a price.' },
  deal: { title: 'Both say yes to the terms', body: 'Once a price is settled, G-One reads the exact terms back to both sides. A deal summary is made only when each says yes to them. It is a record of the call, not a contract.' },
  memory: { title: 'Memory the farmer can erase', body: 'With the farmer’s permission, and when their number can be recognised, G-One can keep a short profile and a gist of the call, so a returning caller can pick up where they left off. Saying “forget me” on a call deletes it. When the number is withheld, nothing is remembered about the caller.' },
}
/**
 * Which of the five points each capability sits behind (1-based, as the points are numbered), so the section after the
 * call reads as the detail under those points rather than a second list of the same ideas. Speech carries every point.
 */
const CAPABILITY_STEPS: Partial<Record<BuiltIcon, number[]>> = {
  speech: [1, 2, 3, 4, 5], memory: [2], consent: [3], translate: [4], whisper: [4], deal: [5],
}
/** In the order the call reaches them, so the list carries on from the points above it. */
const CAPABILITIES = BUILT_FACTS.filter((fact) => fact.icon in CAPABILITY_ICONS)
  .map((fact) => ({ ...fact, ...CAPABILITY_COPY[fact.icon], steps: CAPABILITY_STEPS[fact.icon] ?? [] }))
  .sort((a, b) => a.steps.length === STEPS.length ? -1 : b.steps.length === STEPS.length ? 1 : (a.steps[0] ?? 0) - (b.steps[0] ?? 0))

/** The point numbers a capability sits behind, drawn like the points' own numbers, small. */
function StepRef({ steps }: { steps: number[] }) {
  const every = steps.length === STEPS.length
  const label = every ? 'Behind every step' : `Behind step ${steps.join(' and ')}`
  return (
    <p className="mt-4 flex items-center gap-2.5 text-sm font-semibold text-brand">
      <span aria-hidden="true" className="home-cap-steps">
        {steps.map((n) => <span key={n}>{n}</span>)}
      </span>
      {label}
    </p>
  )
}

/** Page scroll that adds one point: about a quarter of the window, kept between 11 rem and 18 rem. */
const stepDistance = (height: number) => Math.min(288, Math.max(176, height * 0.25))

/** How far a point slides up as it appears, in pixels: 1.5rem, the `.home-point` transform in `index.css`. */
const RISE = 24

/**
 * The window height with a phone browser's toolbars showing (`100svh`), so the layout does not flip between pinned and
 * listed as the toolbars slide away during a scroll. Browsers without `svh` report the plain window height.
 */
function smallViewportHeight(): number {
  const probe = document.createElement('div')
  probe.style.cssText = 'position:fixed;top:0;height:100vh;height:100svh;visibility:hidden;pointer-events:none'
  document.body.appendChild(probe)
  const height = probe.getBoundingClientRect().height || window.innerHeight
  probe.remove()
  return Math.min(height, window.innerHeight)
}

/**
 * How the five points behave. They build up as the visitor scrolls: the first shows alone, then each new point appears
 * below the ones before it, which stay. The call scene always shows the newest point. Scrolling back takes points away
 * again in reverse. The page only reads the scroll position and never moves it.
 * - `stacked` (first paint and server markup): only the first point shows.
 * - `pinned`: the stage (all five places and the call scene) is sticky inside the track, and a spacer after it gives
 *   the track extra scroll height. How far the stage has been held decides how many points show.
 * - `list`: for a window too short to hold the whole stage (every phone, and short laptop windows). The points sit in
 *   the page and each appears once it has scrolled fully into the room for reading. The scene stays in view: beside
 *   the points on wide screens (`dock` side), or docked to the bottom of the window under them on narrow ones (`dock`
 *   bottom), whenever it is small enough to leave room for reading. The first point shows from the start. Docked at the
 *   bottom, the scene never rises above the first point (see `--home-first-point` in `index.css`), and every later point
 *   appears only once it has cleared the scene, so the scene never covers a point that shows.
 *
 * `fit` is how big the stage is drawn. `roomy` is the full design. When that does not fit the window, the stage is
 * redrawn `compact` (phone-size type and tighter spacing, side by side from the `sm` width up) and measured again.
 * When the compact stage does not fit either, the page falls back to the list. If the compact scene is then still too
 * big to stay in view (very short windows, such as a small phone held sideways), the scene is redrawn `mini`, a short
 * card docked at the bottom under the points. Only a window too short even for that leaves the scene in the page
 * after the points.
 *
 * Besides the newest point (React state, so it changes only when a point is added or removed), the scroll handler
 * writes the exact reading progress to the `--home-progress` custom property on the track. The rail between the
 * points' numbers draws itself from it, so that motion follows the scroll continuously without re-rendering.
 */
type PointMode = 'stacked' | 'pinned' | 'list'
type StageFit = SceneSize
type SceneDock = 'none' | 'side' | 'bottom'

interface PointLayout {
  mode: PointMode
  /** Page scroll per point, pinned only. */
  distance: number
  /** Where the pinned stage, or in the list a scene docked beside the points, sits from the top of the window. */
  top: number
  /** List mode only: where the scene stays while the points scroll. */
  dock: SceneDock
}

const sameLayout = (a: PointLayout, b: PointLayout) => a.mode === b.mode && a.distance === b.distance && a.top === b.top && a.dock === b.dock

function useCallSteps() {
  const track = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const [point, setPoint] = useState(0)
  const [fit, setFit] = useState<StageFit>('roomy')
  const [layout, setLayout] = useState<PointLayout>({ mode: 'stacked', distance: 0, top: 0, dock: 'none' })
  const last = STEPS.length - 1

  // Runs again when `fit` changes, so a compact stage is measured once it has been drawn.
  useEffect(() => {
    // Read again on resize only: it adds an element to the page, which a scroll handler should not do.
    let viewport = smallViewportHeight()
    let width = window.innerWidth
    const update = (next: PointLayout) => setLayout((l) => (sameLayout(l, next) ? l : next))
    const show = (t: HTMLElement, progress: number) => {
      // The first point always shows, in every mode, matching the scene's first pane.
      const p = Math.min(last, Math.max(0, progress))
      t.style.setProperty('--home-progress', p.toFixed(3))
      setPoint(Math.floor(p))
    }
    // Scroll events already arrive at most once per frame and this reads a few rectangles, so there is no throttle.
    const measure = () => {
      const t = track.current
      const s = stage.current
      const points = s?.querySelector('ol')
      const scene = s?.querySelector<HTMLElement>('[data-scene]')
      if (!t || !s || !points || !scene) return
      // Side by side from the width where the stage's grid gets two columns (`sm` compact, `lg` roomy; a mini stage is
      // always one column). Asked of the window, not the grid, because the bottom dock draws the stage as a single
      // column at every width.
      const sideBySide = fit !== 'mini' && window.matchMedia(fit === 'compact' ? '(min-width: 40rem)' : '(min-width: 64rem)').matches
      // Every point keeps its place whether it shows or not, so the list is always as tall as all five points.
      const listHeight = points.getBoundingClientRect().height
      const sceneHeight = scene.getBoundingClientRect().height
      const gap = parseFloat(getComputedStyle(s).rowGap) || 0
      const need = sideBySide ? Math.max(listHeight, sceneHeight) : listHeight + gap + sceneHeight
      const header = document.querySelector('header')?.getBoundingClientRect().height ?? 0
      const room = viewport - header - 8
      if (need <= room) {
        const distance = stepDistance(viewport)
        // Centred in the room under the header, never tucked behind it.
        update({ mode: 'pinned', distance, top: Math.round(header + 4 + (room - need) / 2), dock: 'none' })
        show(t, (s.getBoundingClientRect().top - t.getBoundingClientRect().top) / distance)
        return
      }
      if (fit === 'roomy') {
        // Too tall at full size: redraw compact. The effect runs again and measures the compact stage.
        setFit('compact')
        return
      }
      // `offsetTop` and `offsetHeight` ignore the slide a hidden point is drawn with.
      const items = [...points.children] as HTMLElement[]
      let dock: SceneDock
      if (fit === 'mini') {
        // The mini scene docks at the bottom whenever the room above it still holds the tallest point in full, with the
        // slide it rises by (see the reading line below) to spare.
        const tallest = Math.max(...items.map((li) => li.offsetHeight))
        dock = sceneHeight + 12 + tallest + RISE + 4 <= room ? 'bottom' : 'none'
      } else {
        dock = sideBySide ? (sceneHeight <= room ? 'side' : 'none') : sceneHeight <= room * 0.5 ? 'bottom' : 'none'
        if (dock === 'none') {
          // The compact scene would scroll away from its point: redraw it mini. The effect runs again and measures it.
          setFit('mini')
          return
        }
      }
      update({ mode: 'list', distance: 0, top: Math.round(header + 4), dock })
      // Docked at the bottom, the scene may not rise above the first point, which always shows: `index.css` keeps the
      // scene's sticky limit this far below the top of the stage. Written only when it changes, not on every scroll.
      const firstPoint = `${dock === 'bottom' ? items[0].offsetHeight : 0}px`
      if (t.style.getPropertyValue('--home-first-point') !== firstPoint) t.style.setProperty('--home-first-point', firstPoint)
      // A later point appears once its bottom edge rises past the reading line, so it shows in full: docked at the
      // bottom, far enough above the scene that even the slide a point rises in with starts clear of it; otherwise near
      // the bottom of the window.
      const readRoom = dock === 'bottom' ? room - sceneHeight - 12 : room
      const line = header + 4 + (dock === 'bottom' ? readRoom - 4 - RISE : readRoom * 0.9)
      const listTop = points.getBoundingClientRect().top
      show(t, listProgress(items.map((li) => listTop + li.offsetTop + li.offsetHeight), line))
    }
    // The first reading waits a task, so it is not a state update during the effect itself.
    const first = setTimeout(measure, 0)
    const resize = () => {
      const nextViewport = smallViewportHeight()
      const nextWidth = window.innerWidth
      const changed = nextViewport !== viewport || nextWidth !== width
      viewport = nextViewport
      width = nextWidth
      // A new window size may have room for the full stage again, so try it first. Phone toolbars sliding away change
      // neither number (the height is the one with toolbars showing), so scrolling never redraws the stage.
      if (changed && fit !== 'roomy') setFit('roomy')
      else measure()
    }
    window.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', resize)
    return () => {
      clearTimeout(first)
      window.removeEventListener('scroll', measure)
      window.removeEventListener('resize', resize)
    }
  }, [fit, last])

  // Four points to add plus half a step of hold, so the last point stays a moment before the stage moves on.
  const spacer = layout.mode === 'pinned' ? layout.distance * (last + 0.5) : 0
  return { track, stage, point, fit, spacer, ...layout }
}

/**
 * The five points, building up one below another. Every point keeps its place in the list, so adding one never moves
 * the others. Points not reached yet are transparent but stay in the accessibility tree, so a screen reader reads the
 * whole list in order. The newest point is marked current; the ones before it stay readable, a step quieter.
 */
function CallPoints({ point, compact }: { point: number; compact: boolean }) {
  return (
    <ol aria-label="What happens on the call" className="home-points">
      {STEPS.map((s, i) => (
        <li key={s.title} data-point={i} data-state={i === point ? 'current' : i < point ? 'shown' : 'upcoming'} aria-current={i === point ? 'step' : undefined} style={{ '--i': i } as CSSProperties} className="home-point">
          <span aria-hidden="true" className="home-point-num">{i + 1}</span>
          <div className="min-w-0">
            <h3 className={`flex min-h-8 items-center text-xl leading-tight font-semibold ${compact ? '' : 'sm:text-2xl lg:text-[1.625rem]'}`}>{s.title}</h3>
            <p className={`home-point-body mt-1 max-w-[34rem] text-[0.9375rem] leading-snug text-muted ${compact ? '' : 'sm:text-base sm:leading-normal lg:mt-1.5 lg:text-[1.0625rem]'}`}>{s.body}</p>
          </div>
        </li>
      ))}
    </ol>
  )
}

const AUDIENCE: { icon: ReactNode; who: string; body: string; note?: string; tone: string }[] = [
  { icon: <WheatIcon />, who: 'Farmers', body: 'Know the last recorded rate before you agree, sell beyond your own district, and have someone on the line who is on your side.', tone: 'bg-farmer-soft text-farmer' },
  { icon: <StoreIcon />, who: 'Buyers and traders', body: 'Reach growers who speak another language and leave each call with the terms already read back.', tone: 'bg-buyer-soft text-buyer' },
  { icon: <HandshakeIcon />, who: 'Kirana stores', body: 'Order direct from nearby growers by phone, in the same way.', note: 'Planned', tone: 'bg-assistant-soft text-assistant' },
]

const PREVIEW: { who: 'farmer' | 'assistant' | 'buyer' | 'whisper'; label: string; lang: string; text: string; sub: string }[] = [
  { who: 'assistant', label: 'G-One to farmer', lang: 'kn', text: 'ನಮಸ್ಕಾರ ಬಸವರಾಜ್ ಅವರೇ! ಹೋದ ಸಲ ಈರುಳ್ಳಿ rate ಕೇಳಿದ್ರಿ, ನೆನಪಿದೆ.', sub: 'Namaskara, Basavaraj! Last time you asked about the onion rate, I remember.' },
  { who: 'farmer', label: 'Farmer', lang: 'kn', text: 'ಹೌದು. ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ ಇದೆ, ಮಾರಬೇಕು.', sub: 'Yes. I have forty quintals of onion to sell.' },
  { who: 'buyer', label: 'Buyer', lang: 'bn', text: 'দেখুন, বাজার এখন খুব খারাপ।', sub: 'Look, the market is very bad now.' },
  { who: 'whisper', label: 'Only the farmer hears', lang: 'kn', text: 'PIN ಯಾರಿಗೂ ಕೊಡಬೇಡಿ. ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ ತಗೊಳ್ಳಿ.', sub: 'Do not give your PIN to anyone. Take the money when the goods arrive.' },
]

const PREVIEW_TONE = {
  farmer: 'bg-farmer-soft border-t-4 border-farmer',
  buyer: 'bg-buyer-soft border-t-4 border-buyer',
  assistant: 'bg-assistant-soft border-t-4 border-assistant',
  whisper: 'border-2 border-dashed border-whisper-edge bg-whisper-soft',
}

/** Field rows running to the horizon. */
function Furrows({ className = '' }: { className?: string }) {
  const lines = Array.from({ length: 15 }, (_, i) => i - 7)
  return (
    <svg aria-hidden="true" viewBox="0 0 1200 240" preserveAspectRatio="none" className={`pointer-events-none absolute inset-x-0 bottom-0 h-40 w-full md:h-60 ${className}`}>
      {lines.map((n) => (
        <line key={n} x1={600 + n * 26} y1="0" x2={600 + n * 215} y2="240" stroke="currentColor" strokeWidth="1.25" vectorEffect="non-scaling-stroke" />
      ))}
    </svg>
  )
}

/** A section heading. `split` puts the supporting line beside the heading on wide screens instead of under it. */
function SectionHead({ title, id, children, center = false, split = false, night = false }: { title: string; id?: string; children?: ReactNode; center?: boolean; split?: boolean; night?: boolean }) {
  const sub = night ? 'text-on-night-muted' : 'text-muted'
  if (split) {
    return (
      <Reveal className="grid gap-5 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)] lg:items-end lg:gap-16">
        <h2 id={id} className="text-headline font-semibold">{title}</h2>
        {children && <p className={`max-w-xl text-xl lg:pb-1.5 ${sub}`}>{children}</p>}
      </Reveal>
    )
  }
  return (
    <Reveal className={`max-w-3xl ${center ? 'mx-auto text-center' : ''}`}>
      <h2 id={id} className="text-headline font-semibold">{title}</h2>
      {children && <p className={`mt-4 max-w-2xl text-xl ${sub} ${center ? 'mx-auto' : ''}`}>{children}</p>}
    </Reveal>
  )
}

/** Farmer, G-One and buyer on one line: what each side says and what G-One does between them. */
function FlowRibbon() {
  const node = 'flex min-w-0 flex-1 flex-col items-center gap-2 rounded-2xl px-3 py-4 text-center'
  return (
    <figure aria-label="Flow of a call: the farmer talks to G-One, G-One talks to the buyer" className="tile p-3 sm:p-4">
      <div className="flex flex-col items-stretch gap-2 sm:flex-row sm:items-center">
        <div className={`${node} bg-farmer-soft text-farmer`}>
          <span className="grid h-11 w-11 place-items-center rounded-full bg-surface text-xl shadow-card"><WheatIcon /></span>
          <span className="font-bold text-ink">Farmer</span>
          <span className="text-sm text-muted">Speaks in their own language</span>
        </div>
        <ArrowIcon aria-hidden="true" className="mx-auto shrink-0 rotate-90 text-2xl text-line-strong sm:rotate-0" />
        <div className={`${node} bg-night text-on-night ring-4 ring-accent/40`}>
          <span className="grid h-11 w-11 place-items-center rounded-full bg-night-raised text-xl"><Logo className="text-[1.1em] text-on-night-muted" /></span>
          <span className="font-bold">G-One</span>
          <span className="text-sm text-on-night-muted">Translates, advises the farmer, reads the deal back</span>
        </div>
        <ArrowIcon aria-hidden="true" className="mx-auto shrink-0 rotate-90 text-2xl text-line-strong sm:rotate-0" />
        <div className={`${node} bg-buyer-soft text-buyer`}>
          <span className="grid h-11 w-11 place-items-center rounded-full bg-surface text-xl shadow-card"><StoreIcon /></span>
          <span className="font-bold text-ink">Buyer</span>
          <span className="text-sm text-muted">Joins only after saying yes to the call</span>
        </div>
      </div>
    </figure>
  )
}

/** One official figure, ruled off from its neighbours rather than boxed. */
function StatCard({ stat }: { stat: (typeof IMPACT_STATS)[number] }) {
  return (
    <article className="flex h-full flex-col border-t border-night-line pt-6">
      <p className="text-stat font-semibold text-accent">
        {stat.figure.split(' / ').map((part, i) => (
          <span key={part}>{i > 0 && <span aria-hidden="true" className="mx-1.5 font-normal text-on-night-muted">/</span>}{i > 0 && <span className="sr-only"> and </span>}{part}</span>
        ))}
      </p>
      <p className="mt-3 text-lg leading-snug font-medium text-on-night">{stat.label}</p>
      <p className="mt-2 flex-1 text-on-night-muted">{stat.note}</p>
      <a href={stat.source.url} target="_blank" rel="noreferrer" className="mt-5 inline-flex items-center gap-1.5 self-start text-sm font-semibold text-on-night-muted underline decoration-night-line underline-offset-4 transition-colors hover:text-on-night hover:decoration-accent">
        Source: {stat.source.name} <ExternalIcon aria-hidden="true" className="text-[0.9em]" /><span className="sr-only">(opens in a new tab)</span>
      </a>
    </article>
  )
}

export default function LandingPage({ section }: { section: string | null }) {
  const { track, stage, point, fit, mode, top, dock, spacer } = useCallSteps()
  const compact = fit !== 'roomy'
  // Two columns from `sm` (compact) or `lg` (roomy), scene on the left. A mini stage is one column with the scene after
  // the points, where it docks to the bottom of the window. A pinned stage on one column puts the scene first, so the
  // room kept for points still to come stays at the bottom of the stage instead of a gap between point and scene.
  const stageGrid = fit === 'mini' ? 'gap-7' : fit === 'compact' ? 'gap-3 sm:grid-cols-[minmax(0,6fr)_minmax(0,4fr)] sm:gap-6' : 'gap-4 sm:gap-8 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] lg:gap-16'
  const sceneOrder = mode === 'pinned' ? 'order-first' : fit === 'compact' ? 'sm:order-first' : fit === 'roomy' ? 'lg:order-first' : ''

  useEffect(() => {
    // A frame later, so it runs after the shell's scroll to the top on a view change.
    const frame = requestAnimationFrame(() => {
      if (section === 'how') document.getElementById('how')?.scrollIntoView()
      else window.scrollTo({ top: 0 })
    })
    return () => cancelAnimationFrame(frame)
  }, [section])

  // A link to #/how while already there changes no hash, so it has to scroll by itself.
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (location.hash === '#/how' && (e.target as Element).closest?.('a[href="#/how"]')) document.getElementById('how')?.scrollIntoView()
    }
    document.addEventListener('click', onClick)
    return () => document.removeEventListener('click', onClick)
  }, [])

  return (
    <>
      <section className="home-hero relative overflow-hidden">
        <div aria-hidden="true" className="dots pointer-events-none absolute inset-0 [mask-image:radial-gradient(70%_60%_at_20%_30%,black,transparent)]" />
        <div aria-hidden="true" className="pointer-events-none absolute -top-40 right-[-10%] h-[36rem] w-[36rem] rounded-full bg-accent/8 blur-3xl" />
        <Furrows className="text-line" />
        <div className="shell relative grid items-center gap-12 pt-12 pb-16 md:pt-16 lg:grid-cols-[minmax(0,1.08fr)_minmax(0,1fr)] lg:gap-14 lg:pt-20 lg:pb-24">
          <div>
            <h1 className="text-display font-semibold">
              The voice in the middle, on the <span className="bg-[linear-gradient(transparent_62%,var(--accent-soft)_62%,var(--accent-soft)_92%,transparent_92%)] text-brand">farmer's side.</span>
            </h1>
            <p className="mt-6 max-w-xl text-xl text-muted md:text-[1.375rem] md:leading-snug">
              G-One brings in a buyer, translates both ways, and quietly tells the farmer what the buyer can't hear. Try it in your browser; a phone line is planned.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row sm:flex-wrap lg:flex-nowrap">
              <a href="#/demo" className="btn-accent btn-lg px-5"><PlayIcon /> Watch a call</a>
              <a href="#/call" className="btn-quiet btn-lg px-5"><PhoneIcon /> Try a call</a>
              <a href="#/how" className="btn-ghost btn-lg px-4 text-brand">Learn how it works <ArrowIcon /></a>
            </div>
          </div>
          <div className="relative mx-auto w-full max-w-[34rem] lg:max-w-none">
            <div aria-hidden="true" className="absolute inset-4 -z-0 translate-y-4 rounded-[2.5rem] bg-accent/10 blur-2xl" />
            <HeroArt className="relative drop-shadow-[0_24px_40px_rgb(60_45_15/0.18)]" />
          </div>
        </div>
      </section>

      <section>
        <div className="shell">
          <div className="home-section">
            <SectionHead title="A good harvest can still be a bad sale." split>
              The hardest part of selling a crop often happens on a phone, where the other side knows more.
            </SectionHead>
            <div className="mt-10 grid gap-x-8 gap-y-10 sm:grid-cols-2 md:mt-12 lg:grid-cols-4">
              {PROBLEMS.map((p, i) => (
                <Reveal key={p.title} delay={i * 80} className="h-full">
                  <article className="h-full border-t border-line-strong/40 pt-6">
                    <span className="grid h-11 w-11 place-items-center rounded-xl bg-assistant-soft text-xl text-assistant">{p.icon}</span>
                    <h3 className="mt-5 text-2xl leading-tight font-semibold">{p.title}</h3>
                    <p className="mt-2 text-muted">{p.body}</p>
                  </article>
                </Reveal>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section aria-labelledby="numbers" className="home-stage on-night relative overflow-hidden bg-night text-on-night">
        <div aria-hidden="true" className="dots-night pointer-events-none absolute inset-0 [mask-image:linear-gradient(to_bottom,black,transparent_70%)]" />
        <div aria-hidden="true" className="pointer-events-none absolute -right-32 -bottom-48 h-[30rem] w-[30rem] rounded-full bg-accent/15 blur-3xl" />
        <div className="shell home-section relative">
          <SectionHead title="The problem in numbers." id="numbers" split night>
            Official figures on small farms, phones, cybercrime and languages, each with its year and source.
          </SectionHead>
          <div className="mt-10 grid gap-x-10 gap-y-12 md:mt-12 md:grid-cols-2 lg:grid-cols-3">
            {IMPACT_STATS.map((s, i) => (
              <Reveal key={s.id} delay={(i % 3) * 80} className="h-full">
                <StatCard stat={s} />
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* No bottom padding or rule: the section after it carries on from the last point instead of starting over. */}
      <section id="how" className="home-section pb-0 md:pb-0">
        <div className="shell">
          <SectionHead title="One call. Five things happen.">
            The farmer stays on one call. G-One does the rest around them.
          </SectionHead>
          <Reveal className="mt-10 md:mt-12">
            <FlowRibbon />
          </Reveal>
          {/*
            Pinned, the stage stays in view while the spacer after it scrolls past; that extra scroll adds the points.
            A sticky element cannot travel into its container's padding, so the extra height is a real (empty) element.
            The points come first in the source so they read first; on wide screens the scene sits to their left.
          */}
          <div ref={track} data-mode={mode} data-fit={fit} data-dock={mode === 'list' && dock !== 'none' ? dock : undefined} style={{ '--home-pin-top': `${top}px` } as CSSProperties} className="home-track mt-10 md:mt-14">
            <div ref={stage} className={`home-track-stage grid grid-cols-1 items-center ${stageGrid}`}>
              <CallPoints point={point} compact={compact} />
              <div data-scene className={`min-w-0 ${sceneOrder}`}>
                <Reveal>
                  <CallScene step={point} size={fit} />
                </Reveal>
              </div>
            </div>
            {spacer > 0 && <div aria-hidden="true" data-spacer style={{ height: spacer }} />}
          </div>
        </div>
      </section>

      <section>
        <div className="shell">
          <div className="home-section home-after-call grid gap-10 lg:grid-cols-[minmax(0,4fr)_minmax(0,8fr)] lg:gap-16">
            <div className="lg:sticky lg:top-28 lg:self-start">
              <SectionHead title="What each step rests on.">
                The parts that carry the call above, each tied to the step it serves.
              </SectionHead>
            </div>
            <ul className="grid gap-x-10 gap-y-10 sm:grid-cols-2">
              {CAPABILITIES.map((c, i) => (
                <li key={c.title}>
                  <Reveal delay={(i % 2) * 70} className="h-full">
                    <article className="flex h-full gap-4 border-t border-line-strong/40 pt-5">
                      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-lg text-brand">{CAPABILITY_ICONS[c.icon]}</span>
                      <div className="min-w-0">
                        <h3 className="text-xl leading-tight font-semibold">{c.title}</h3>
                        <p className="mt-2 text-muted">{c.body}</p>
                        <StepRef steps={c.steps} />
                      </div>
                    </article>
                  </Reveal>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      <section>
        <div className="shell">
          <div className="home-section border-t border-line">
            <div className="flex flex-wrap items-end justify-between gap-x-8 gap-y-6">
              <SectionHead title="Two languages, one deal." />
              <a href="#/demo?autoplay=1" className="btn-primary btn-lg"><PlayIcon /> Watch a call</a>
            </div>
            <Reveal className="mt-10 md:mt-12">
              <div className="scrollbar-none -mx-4 flex snap-x gap-4 overflow-x-auto px-4 sm:-mx-6 sm:px-6 lg:mx-0 lg:grid lg:grid-cols-4 lg:px-0">
                {PREVIEW.map((p) => (
                  <article key={p.label} className={`w-72 shrink-0 snap-start rounded-2xl p-5 lg:w-auto ${PREVIEW_TONE[p.who]}`}>
                    <p className="flex items-center gap-1.5 text-sm font-bold text-muted">{p.who === 'whisper' && <LockIcon />}{p.label}</p>
                    <p lang={p.lang} className="mt-2 text-xl leading-snug font-semibold">{p.text}</p>
                    <p lang="en" className="mt-2 text-muted">{p.sub}</p>
                  </article>
                ))}
              </div>
              {/* <p className="mt-6 text-sm text-faint">An illustrative conversation.</p> */}
            </Reveal>
          </div>
        </div>
      </section>

      <section>
        <div className="shell">
          <div className="home-section border-t border-line">
            <SectionHead title="Both ends of the call, and the farmer first." center />
            <div className="mt-10 grid gap-x-10 gap-y-10 md:mt-12 md:grid-cols-3">
              {AUDIENCE.map((a, i) => (
                <Reveal key={a.who} delay={i * 80} className="h-full">
                  <article className="h-full border-t border-line-strong/40 pt-6">
                    <span className={`grid h-11 w-11 place-items-center rounded-xl text-xl ${a.tone}`}>{a.icon}</span>
                    <h3 className="mt-5 flex flex-wrap items-center gap-x-3 gap-y-1 text-2xl font-semibold">{a.who}{a.note && <span className="badge">{a.note}</span>}</h3>
                    <p className="mt-2 text-muted">{a.body}</p>
                  </article>
                </Reveal>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="pb-2 sm:pb-3 lg:pb-4">
        <div className="home-stage on-night relative overflow-hidden bg-night text-on-night">
          <div aria-hidden="true" className="pointer-events-none absolute top-[58%] left-1/2 h-[28rem] w-[28rem] -translate-x-1/2 rounded-full bg-accent/25 blur-3xl" />
          <Furrows className="text-night-line" />
          <div className="shell relative py-20 text-center md:py-24">
            <h2 className="mx-auto max-w-3xl text-headline font-semibold">Hear a deal happen across two languages.</h2>
            <p className="mx-auto mt-4 max-w-xl text-xl text-on-night-muted">Listen to an illustrative conversation in about three minutes.</p>
            <div className="mt-10 flex flex-col justify-center gap-3 sm:flex-row">
              <a href="#/demo?autoplay=1" className="btn-accent btn-lg"><PlayIcon /> Play the conversation</a>
              <a href="#/call" className="btn-night btn-lg"><PhoneIcon /> Try a call</a>
              <a href="#/farmers" className="btn-night btn-lg">See what it remembers</a>
            </div>
          </div>
        </div>
      </section>
    </>
  )
}
