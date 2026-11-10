import { useEffect, type ReactNode } from 'react'
import CallScene from '../landing/CallScene'
import HeroArt from '../landing/HeroArt'
import { BUILT_FACTS, LANGUAGES, type BuiltIcon } from '../landing/builtFacts'
import { IMPACT_FOOTNOTE, IMPACT_STATS } from '../landing/impactStats'
import {
  AlertIcon,
  ArrowIcon,
  BeakerIcon,
  CheckIcon,
  ClockIcon,
  DoubleCheckIcon,
  ExternalIcon,
  GlobeIcon,
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

const STEPS: { title: string; body: string; art: ReactNode }[] = [
  {
    title: 'The farmer calls',
    body: 'Designed for an ordinary phone call, so there is nothing to install. In this demo the call runs in the browser.',
    art: (
      <div className="flex items-center gap-3">
        <span className="relative grid h-11 w-11 shrink-0 place-items-center rounded-full bg-farmer-soft text-xl text-farmer">
          <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-farmer" />
          <PhoneIcon />
        </span>
        <span className="text-sm"><span className="block font-bold">Calling G-One</span><span className="text-muted">No app, no sign-up</span></span>
      </div>
    ),
  },
  {
    title: 'G-One knows them',
    body: 'It greets a returning caller by name, recalls the last call, and answers questions on prices and weather.',
    art: (
      <div className="flex flex-wrap gap-1.5 text-sm">
        <span className="rounded-lg bg-surface px-2.5 py-1 shadow-card"><span className="font-bold">Last call:</span> asked about onion rates</span>
        <span className="rounded-lg bg-surface px-2.5 py-1 shadow-card"><span className="font-bold">Grows:</span> onion, Gadag</span>
      </div>
    ),
  },
  {
    title: 'It brings the right buyer',
    body: 'G-One matches the crop and quantity to a buyer, asks the buyer first, and brings them in on a second line.',
    art: (
      <div className="flex flex-wrap gap-1.5 text-sm">
        <span className="flex items-center gap-1.5 rounded-lg bg-surface px-2.5 py-1 font-bold shadow-card ring-2 ring-buyer">Mondal Traders <CheckIcon className="text-buyer" /></span>
        <span className="rounded-lg bg-surface px-2.5 py-1 text-muted shadow-card">Patil Agro</span>
      </div>
    ),
  },
  {
    title: 'It translates, advises, confirms',
    body: 'Each side hears the other in their own language. The farmer alone hears the advice. The deal is read back and needs two yeses.',
    art: (
      <div className="flex flex-wrap gap-1.5 text-sm">
        <span className="flex items-center gap-1.5 rounded-lg border border-dashed border-whisper-edge bg-whisper-soft px-2.5 py-1 font-semibold text-whisper"><LockIcon /> Private note to farmer</span>
        <span className="flex items-center gap-3 rounded-lg bg-surface px-2.5 py-1 font-bold shadow-card"><span className="flex items-center gap-1 text-farmer"><CheckIcon /> Yes</span><span className="flex items-center gap-1 text-buyer"><CheckIcon /> Yes</span></span>
      </div>
    ),
  },
]

const BUILT_ICON: Record<BuiltIcon, ReactNode> = {
  speech: <MicIcon />,
  translate: <LanguageIcon />,
  whisper: <LockIcon />,
  consent: <UserCheckIcon />,
  deal: <DoubleCheckIcon />,
  memory: <MemoryIcon />,
  tests: <BeakerIcon />,
  languages: <GlobeIcon />,
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

function SectionHead({ kicker, title, children, center = false }: { kicker: string; title: string; children?: ReactNode; center?: boolean }) {
  return (
    <Reveal className={`max-w-3xl ${center ? 'mx-auto text-center' : ''}`}>
      <p className="kicker">{kicker}</p>
      <h2 className="mt-3 text-headline font-semibold">{title}</h2>
      {children && <p className={`mt-4 text-xl text-muted ${center ? 'mx-auto max-w-2xl' : ''}`}>{children}</p>}
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
          <span className="text-sm text-muted">Joins after saying yes</span>
        </div>
      </div>
    </figure>
  )
}

function StatCard({ stat }: { stat: (typeof IMPACT_STATS)[number] }) {
  return (
    <article className="flex h-full flex-col rounded-2xl border border-night-line bg-night-raised p-6">
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
      <section className="relative overflow-hidden">
        <div aria-hidden="true" className="dots pointer-events-none absolute inset-0 [mask-image:radial-gradient(70%_60%_at_20%_30%,black,transparent)]" />
        <div aria-hidden="true" className="pointer-events-none absolute -top-40 right-[-10%] h-[36rem] w-[36rem] rounded-full bg-accent/20 blur-3xl" />
        <Furrows className="text-line" />
        <div className="shell relative grid items-center gap-10 pt-10 pb-20 md:pt-14 lg:grid-cols-[minmax(0,1.12fr)_minmax(0,1fr)] lg:gap-12 lg:pt-16 lg:pb-24">
          <div>
            <p className="inline-flex items-center gap-2 rounded-full border border-line bg-surface/80 px-3 py-1 text-sm font-semibold text-muted shadow-card">
              <span aria-hidden="true" className="h-2 w-2 rounded-full bg-brand" /> Browser demo · synthetic data
            </p>
            <h1 className="mt-6 text-display font-semibold">
              The voice in the middle, on the <span className="bg-[linear-gradient(transparent_62%,var(--accent-soft)_62%,var(--accent-soft)_92%,transparent_92%)] text-brand">farmer's side.</span>
            </h1>
            <p className="mt-6 max-w-xl text-xl text-muted md:text-[1.375rem] md:leading-snug">
              A browser demo of a phone-call middleman. G-One brings in a buyer, translates both ways, and quietly tells the farmer what the buyer can't hear.
            </p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap lg:flex-nowrap">
              <a href="#/demo" className="btn-accent btn-lg px-5"><PlayIcon /> Watch the demo</a>
              <a href="#/call" className="btn-quiet btn-lg px-5"><PhoneIcon /> Try a call</a>
              <a href="#/how" className="btn-ghost btn-lg px-4 text-brand">See how it works <ArrowIcon /></a>
            </div>
            <dl className="mt-8 grid max-w-xl gap-3 text-sm sm:grid-cols-2">
              <div className="rounded-xl border border-line bg-surface/70 px-3.5 py-2.5">
                <dt className="font-bold text-ink">Exercised in live tests</dt>
                <dd className="mt-0.5 text-muted"><span lang="hi">हिंदी</span>, English, Hinglish</dd>
              </div>
              <div className="rounded-xl border border-line bg-surface/70 px-3.5 py-2.5">
                <dt className="font-bold text-ink">Configured, awaiting review</dt>
                <dd className="mt-0.5 text-muted"><span lang="bn">বাংলা</span>, Tamil, <span lang="kn">ಕನ್ನಡ</span></dd>
              </div>
            </dl>
          </div>
          <div className="relative mx-auto w-full max-w-[34rem] lg:max-w-none">
            <div aria-hidden="true" className="absolute inset-4 -z-0 translate-y-4 rounded-[2.5rem] bg-accent/25 blur-2xl" />
            <HeroArt className="relative drop-shadow-[0_24px_40px_rgb(60_45_15/0.18)]" />
          </div>
        </div>
      </section>

      <section className="border-t border-line bg-surface py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="The problem" title="A good harvest can still be a bad sale.">
            The hardest part of selling a crop often happens on a phone, where the other side knows more.
          </SectionHead>
          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {PROBLEMS.map((p, i) => (
              <Reveal key={p.title} delay={i * 80}>
                <article className="tile card-hover h-full p-6">
                  <span className="grid h-12 w-12 place-items-center rounded-xl bg-assistant-soft text-2xl text-assistant">{p.icon}</span>
                  <h3 className="mt-4 text-2xl leading-tight font-semibold">{p.title}</h3>
                  <p className="mt-2 text-muted">{p.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
          <p className="mt-6 text-sm text-faint">Problems farmers commonly describe, in plain words. G-One has not run a field study.</p>
        </div>
      </section>

      <section aria-labelledby="numbers" className="on-night relative overflow-hidden bg-night py-20 text-on-night md:py-28">
        <div aria-hidden="true" className="dots-night pointer-events-none absolute inset-0 [mask-image:linear-gradient(to_bottom,black,transparent_70%)]" />
        <div aria-hidden="true" className="pointer-events-none absolute -right-32 -bottom-48 h-[30rem] w-[30rem] rounded-full bg-accent/15 blur-3xl" />
        <div className="shell relative">
          <Reveal className="max-w-3xl">
            <p className="kicker">Why it matters</p>
            <h2 id="numbers" className="mt-3 text-headline font-semibold">The problem in numbers.</h2>
            <p className="mt-4 text-xl text-on-night-muted">Official figures on small farms, phones, cybercrime complaints and languages. Each card names its year, who was counted, and its source.</p>
          </Reveal>
          <div className="mt-12 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {IMPACT_STATS.map((s, i) => (
              <Reveal key={s.id} delay={(i % 3) * 80} className="h-full">
                <StatCard stat={s} />
              </Reveal>
            ))}
          </div>
          <p className="mt-8 max-w-3xl text-on-night-muted">{IMPACT_FOOTNOTE}</p>
        </div>
      </section>

      <section id="how" className="py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="How it works" title="One call. Four things happen.">
            The farmer stays on one call. G-One does the rest around them.
          </SectionHead>
          <Reveal className="mt-12">
            <FlowRibbon />
          </Reveal>
          <div className="mt-12 grid grid-cols-1 items-start gap-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <ol className="relative grid gap-10">
            <span aria-hidden="true" className="absolute top-11 bottom-6 left-[1.3rem] w-px bg-line-strong/50" />
            {STEPS.map((s, i) => (
              <li key={s.title} className="relative">
                <Reveal delay={i * 100} className="flex h-full gap-5">
                  <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-brand text-lg font-bold text-on-brand shadow-brand">{i + 1}</span>
                  <div className="min-w-0">
                    <h3 className="text-2xl leading-tight font-semibold">{s.title}</h3>
                    <p className="mt-2 text-muted">{s.body}</p>
                    <div className="mt-4 rounded-2xl bg-sunk p-3">{s.art}</div>
                  </div>
                </Reveal>
              </li>
            ))}
          </ol>
          <Reveal className="min-w-0 lg:sticky lg:top-24">
            <CallScene />
          </Reveal>
          </div>
        </div>
      </section>

      <section className="border-t border-line bg-surface py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="What is built" title="What is built, honestly.">
            What runs in this demo today, and where it stops. We quote no speed figures.
          </SectionHead>
          <ul className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {BUILT_FACTS.map((f, i) => (
              <li key={f.icon}>
                <Reveal delay={(i % 4) * 70} className="h-full">
                  <article className="tile h-full p-5">
                    <span className="grid h-11 w-11 place-items-center rounded-xl bg-brand-soft text-xl text-brand">{BUILT_ICON[f.icon]}</span>
                    <h3 className="mt-4 text-xl leading-tight font-semibold">{f.title}</h3>
                    <p className="mt-2 text-muted">{f.body}</p>
                  </article>
                </Reveal>
              </li>
            ))}
          </ul>
          <p className="mt-6 flex flex-wrap items-center gap-2 text-sm text-faint">
            <span className="badge badge-warn">Phone line in testing</span>
            This demo runs in the browser. No phone number is dialled. Languages live-tested so far: {LANGUAGES.liveTested.join(', ')}.
          </p>
        </div>
      </section>

      <section className="py-20 md:py-28">
        <div className="shell">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <SectionHead kicker="See a conversation" title="Two languages, one deal." />
            <a href="#/demo?autoplay=1" className="btn-primary btn-lg"><PlayIcon /> Watch the sample call</a>
          </div>
        </div>
        <Reveal className="mt-10">
          <div className="shell">
            <div className="scrollbar-none -mx-4 flex snap-x gap-4 overflow-x-auto px-4 pb-2 sm:-mx-6 sm:px-6 lg:mx-0 lg:grid lg:grid-cols-4 lg:px-0">
              {PREVIEW.map((p) => (
                <article key={p.label} className={`w-72 shrink-0 snap-start rounded-2xl p-5 lg:w-auto ${PREVIEW_TONE[p.who]}`}>
                  <p className="flex items-center gap-1.5 text-sm font-bold text-muted">{p.who === 'whisper' && <LockIcon />}{p.label}</p>
                  <p lang={p.lang} className="mt-2 text-xl leading-snug font-semibold">{p.text}</p>
                  <p lang="en" className="mt-2 text-muted">{p.sub}</p>
                </article>
              ))}
            </div>
            <p className="mt-4 text-sm text-faint">From the scripted sample call, voiced with synthetic voices.</p>
          </div>
        </Reveal>
      </section>

      <section className="border-t border-line bg-surface py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="Who it's for" title="Both ends of the call, and the farmer first." />
          <div className="mt-12 grid gap-4 md:grid-cols-3">
            {AUDIENCE.map((a, i) => (
              <Reveal key={a.who} delay={i * 80} className="h-full">
                <article className="tile h-full p-6">
                  <div className="flex items-center justify-between">
                    <span className={`grid h-12 w-12 place-items-center rounded-xl text-2xl ${a.tone}`}>{a.icon}</span>
                    {a.note && <span className="badge">{a.note}</span>}
                  </div>
                  <h3 className="mt-4 text-2xl font-semibold">{a.who}</h3>
                  <p className="mt-2 text-muted">{a.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="on-night relative overflow-hidden bg-night text-on-night">
        <div aria-hidden="true" className="pointer-events-none absolute top-[58%] left-1/2 h-[28rem] w-[28rem] -translate-x-1/2 rounded-full bg-accent/25 blur-3xl" />
        <Furrows className="text-night-line" />
        <div className="shell relative py-24 text-center md:py-32">
          <h2 className="mx-auto max-w-3xl text-headline font-semibold">Hear a deal happen across two languages.</h2>
          <p className="mx-auto mt-4 max-w-xl text-xl text-on-night-muted">The sample call needs no setup and takes about 25 seconds.</p>
          <div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row">
            <a href="#/demo?autoplay=1" className="btn-accent btn-lg"><PlayIcon /> Play the sample call</a>
            <a href="#/call" className="btn-night btn-lg"><PhoneIcon /> Try a call</a>
            <a href="#/farmers" className="btn-night btn-lg">See what it remembers</a>
          </div>
        </div>
      </section>
    </>
  )
}
