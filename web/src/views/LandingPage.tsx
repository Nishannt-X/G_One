import { useEffect, type ReactNode } from 'react'
import CallScene from '../landing/CallScene'
import { AlertIcon, ArrowIcon, CheckIcon, ClockIcon, HandshakeIcon, LanguageIcon, LockIcon, MemoryIcon, PhoneIcon, PlayIcon, ShieldIcon, StoreIcon, WheatIcon } from '../ui/icons'
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
    body: 'An ordinary phone call in Kannada, Bengali, Hindi or English. Nothing to install.',
    art: (
      <div className="flex h-full items-center justify-center">
        <span className="relative grid h-16 w-16 place-items-center rounded-full bg-farmer-soft text-3xl text-farmer">
          <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-farmer" />
          <PhoneIcon />
        </span>
      </div>
    ),
  },
  {
    title: 'G-One knows them',
    body: 'It greets a returning caller by name, recalls the last call, and answers questions on prices and weather.',
    art: (
      <div className="flex h-full flex-col justify-center gap-1.5 text-sm">
        <p className="rounded-lg bg-surface px-2.5 py-1.5"><span className="font-bold">Last call:</span> asked about onion rates</p>
        <p className="rounded-lg bg-surface px-2.5 py-1.5"><span className="font-bold">Grows:</span> onion, Gadag</p>
      </div>
    ),
  },
  {
    title: 'It brings the right buyer',
    body: 'G-One matches the crop and quantity to a buyer, and dials them in on a second line.',
    art: (
      <div className="flex h-full flex-col justify-center gap-1.5 text-sm">
        <p className="flex items-center justify-between rounded-lg bg-surface px-2.5 py-1.5 ring-2 ring-buyer"><span className="font-bold">Mondal Traders</span><CheckIcon className="text-buyer" /></p>
        <p className="rounded-lg bg-surface px-2.5 py-1.5 text-muted">Patil Agro</p>
      </div>
    ),
  },
  {
    title: 'It translates, advises, confirms',
    body: 'Each side hears the other in their own language. The farmer alone hears the advice. The deal is read back and needs two yeses.',
    art: (
      <div className="flex h-full flex-col justify-center gap-1.5 text-sm">
        <p className="flex items-center gap-1.5 rounded-lg border border-dashed border-whisper-edge bg-whisper-soft px-2.5 py-1.5 font-semibold text-whisper"><LockIcon /> Private note to farmer</p>
        <p className="flex items-center gap-3 rounded-lg bg-surface px-2.5 py-1.5 font-bold"><span className="flex items-center gap-1 text-farmer"><CheckIcon /> Yes</span><span className="flex items-center gap-1 text-buyer"><CheckIcon /> Yes</span></p>
      </div>
    ),
  },
]

type Status = { label: string; tone: 'ok' | 'warn' | 'plain' }

const FEATURES: { icon: ReactNode; title: string; body: string; status: Status }[] = [
  { icon: <LanguageIcon />, title: 'Speaks their language', body: 'Kannada, Bengali, Hindi and English on the call. Each side hears its own tongue.', status: { label: 'Working in the simulator', tone: 'ok' } },
  { icon: <MemoryIcon />, title: 'Remembers every farmer', body: 'A short gist of each call and facts marked as told or inferred. Stored against a hash, never the number.', status: { label: 'Working', tone: 'ok' } },
  { icon: <ShieldIcon />, title: 'Private price and scam cautions', body: "Compares an offer with a dated mandi reference and flags requests like an OTP. The buyer can't hear it.", status: { label: 'Sample mandi data', tone: 'warn' } },
  { icon: <StoreIcon />, title: 'Buyer matching', body: 'Finds buyers by crop, quantity and district, then connects them on a second call leg.', status: { label: 'Synthetic buyer list', tone: 'warn' } },
  { icon: <HandshakeIcon />, title: 'Deal read-back with two yeses', body: 'G-One repeats the terms and waits for both sides. It records what was said; it is not a contract.', status: { label: 'Working, nonbinding', tone: 'ok' } },
  { icon: <PhoneIcon />, title: 'Works from any phone', body: 'Designed for a plain voice call, so a feature phone is enough. No app, no data pack.', status: { label: 'Demo runs in browser; phone line in testing', tone: 'plain' } },
]

const AUDIENCE: { icon: ReactNode; who: string; body: string; note?: string }[] = [
  { icon: <WheatIcon />, who: 'Farmers', body: 'Know the last recorded rate before you agree, sell beyond your own district, and have someone on the line who is on your side.' },
  { icon: <StoreIcon />, who: 'Buyers and traders', body: 'Reach growers who speak another language and leave each call with the terms already read back.' },
  { icon: <HandshakeIcon />, who: 'Kirana stores', body: 'Order direct from nearby growers by phone, in the same way.', note: 'Planned' },
]

const PREVIEW: { who: 'farmer' | 'assistant' | 'buyer' | 'whisper'; label: string; lang: string; text: string; sub: string }[] = [
  { who: 'assistant', label: 'G-One to farmer', lang: 'kn', text: 'ನಮಸ್ಕಾರ ಬಸವರಾಜ್ ಅವರೇ! ಹೋದ ಸಲ ಈರುಳ್ಳಿ rate ಕೇಳಿದ್ರಿ, ನೆನಪಿದೆ.', sub: 'Namaskara, Basavaraj! Last time you asked about the onion rate, I remember.' },
  { who: 'farmer', label: 'Farmer', lang: 'kn', text: 'ಹೌದು. ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ ಇದೆ, ಮಾರಬೇಕು.', sub: 'Yes. I have forty quintals of onion to sell.' },
  { who: 'buyer', label: 'Buyer', lang: 'bn', text: 'দেখুন, বাজার এখন খুব খারাপ।', sub: 'Look, the market is very bad now.' },
  { who: 'whisper', label: 'Only the farmer hears', lang: 'kn', text: 'PIN ಯಾರಿಗೂ ಕೊಡಬೇಡಿ. ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ ತಗೊಳ್ಳಿ.', sub: 'Do not give your PIN to anyone. Take the money when the goods arrive.' },
]

const PREVIEW_TONE = {
  farmer: 'bg-farmer-soft',
  buyer: 'bg-buyer-soft',
  assistant: 'bg-assistant-soft',
  whisper: 'border border-dashed border-whisper-edge bg-whisper-soft',
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

function SectionHead({ kicker, title, children }: { kicker: string; title: string; children?: ReactNode }) {
  return (
    <Reveal className="max-w-3xl">
      <p className="kicker">{kicker}</p>
      <h2 className="mt-3 text-headline font-semibold">{title}</h2>
      {children && <p className="mt-4 text-xl text-muted">{children}</p>}
    </Reveal>
  )
}

function StatusBadge({ status }: { status: Status }) {
  const tone = status.tone === 'ok' ? 'badge-ok' : status.tone === 'warn' ? 'badge-warn' : ''
  return <span className={`badge ${tone}`}>{status.label}</span>
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
        <Furrows className="text-line" />
        <div className="shell relative grid items-center gap-10 pt-12 pb-20 md:pt-16 lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)] lg:gap-14 lg:pt-20 lg:pb-28">
          <div>
            <p className="kicker">Voice AI for Indian farmers</p>
            <h1 className="mt-4 text-display font-semibold">The voice in the middle, on the farmer's side.</h1>
            <p className="mt-6 max-w-xl text-xl text-muted md:text-[1.375rem] md:leading-snug">
              G-One takes a farmer's ordinary phone call, finds a buyer, translates both ways, and quietly tells the farmer what the buyer can't hear.
            </p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap">
              <a href="#/demo" className="btn-accent btn-lg"><PlayIcon /> Watch the demo</a>
              <a href="#/call" className="btn-quiet btn-lg"><PhoneIcon /> Try a call</a>
              <a href="#/how" className="btn-quiet btn-lg">See how it works <ArrowIcon /></a>
            </div>
            <p className="mt-6 flex flex-wrap items-center gap-x-3 gap-y-1 text-lg font-medium text-muted">
              <span lang="kn">ಕನ್ನಡ</span><span aria-hidden="true" className="text-line-strong">·</span>
              <span lang="bn">বাংলা</span><span aria-hidden="true" className="text-line-strong">·</span>
              <span lang="hi">हिंदी</span><span aria-hidden="true" className="text-line-strong">·</span>
              <span>English</span>
            </p>
          </div>
          <CallScene />
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
                <article className="card-hover h-full rounded-2xl border border-line bg-page p-6">
                  <span className="text-3xl text-assistant">{p.icon}</span>
                  <h3 className="mt-4 text-2xl leading-tight font-semibold">{p.title}</h3>
                  <p className="mt-2 text-muted">{p.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
          <p className="mt-6 text-sm text-faint">These are the problems farmers commonly describe, stated in plain words. G-One has not run a field study, so we quote no figures.</p>
        </div>
      </section>

      <section id="how" className="py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="How it works" title="One call. Four things happen.">
            The farmer never leaves the phone call. G-One does the rest around them.
          </SectionHead>
          <ol className="relative mt-14 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
            <span aria-hidden="true" className="absolute top-5 right-[24%] left-5 hidden h-px bg-line-strong lg:block" />
            {STEPS.map((s, i) => (
              <li key={s.title}>
                <Reveal delay={i * 120} className="relative h-full">
                  <span className="relative grid h-10 w-10 place-items-center rounded-full bg-brand text-lg font-bold text-on-brand ring-8 ring-page">{i + 1}</span>
                  <div className="mt-5 h-32 rounded-2xl bg-sunk p-4">{s.art}</div>
                  <h3 className="mt-4 text-2xl leading-tight font-semibold">{s.title}</h3>
                  <p className="mt-2 text-muted">{s.body}</p>
                </Reveal>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="border-t border-line bg-surface py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="What it does" title="Built around how a sale really goes.">
            Each badge says how far that part has got today.
          </SectionHead>
          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map((f, i) => (
              <Reveal key={f.title} delay={(i % 3) * 80}>
                <article className="card-hover flex h-full flex-col rounded-2xl border border-line bg-page p-6">
                  <span className="grid h-12 w-12 place-items-center rounded-xl bg-farmer-soft text-2xl text-farmer">{f.icon}</span>
                  <h3 className="mt-4 text-2xl leading-tight font-semibold">{f.title}</h3>
                  <p className="mt-2 flex-1 text-muted">{f.body}</p>
                  <div className="mt-4"><StatusBadge status={f.status} /></div>
                </article>
              </Reveal>
            ))}
          </div>
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
          </div>
        </Reveal>
      </section>

      <section className="border-t border-line bg-surface py-20 md:py-28">
        <div className="shell">
          <SectionHead kicker="Who it's for" title="Both ends of the call, and the farmer first." />
          <div className="mt-12 grid gap-4 md:grid-cols-3">
            {AUDIENCE.map((a, i) => (
              <Reveal key={a.who} delay={i * 80}>
                <article className="h-full border-t-2 border-ink pt-5">
                  <div className="flex items-center justify-between">
                    <span className="text-3xl text-brand">{a.icon}</span>
                    {a.note && <span className="badge">{a.note}</span>}
                  </div>
                  <h3 className="mt-3 text-2xl font-semibold">{a.who}</h3>
                  <p className="mt-1 text-muted">{a.body}</p>
                </article>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="on-night relative overflow-hidden bg-night text-on-night">
        <Furrows className="text-night-line" />
        <div className="shell relative py-24 text-center md:py-32">
          <h2 className="mx-auto max-w-3xl text-headline font-semibold">Hear a deal happen across two languages.</h2>
          <p className="mx-auto mt-4 max-w-xl text-xl text-on-night-muted">The sample call needs no setup and takes about 25 seconds.</p>
          <div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row">
            <a href="#/demo?autoplay=1" className="btn-accent btn-lg"><PlayIcon /> Play the sample call</a>
            <a href="#/farmers" className="btn-night btn-lg">See what it remembers</a>
          </div>
        </div>
      </section>
    </>
  )
}
