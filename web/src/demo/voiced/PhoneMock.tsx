import type { LegRole } from '../../types/console'
import { Logo } from '../../ui/icons'
import type { Manifest, PhoneMessage } from './manifest'
import { SAMPLE_RECEIPT_HREF } from './sampleReceipt'

const PHONE_WALL = { farmer: 'phone-wall-farmer', buyer: 'phone-wall-buyer' }

function StatusBar({ time }: { time: string }) {
  return (
    <div aria-hidden="true" className="relative z-10 flex h-5 items-center justify-between px-3.5 text-[0.625rem] font-semibold text-white/85 tabular-nums">
      <span>{time}</span>
      <span className="flex items-center gap-1">
        <svg viewBox="0 0 14 10" className="h-2 w-2.5 fill-current"><rect x="0" y="7" width="2" height="3" rx=".5" /><rect x="4" y="5" width="2" height="5" rx=".5" /><rect x="8" y="3" width="2" height="7" rx=".5" /><rect x="12" y="0" width="2" height="10" rx=".5" /></svg>
        <svg viewBox="0 0 20 10" className="h-2 w-4 fill-none stroke-current"><rect x=".5" y=".5" width="16" height="9" rx="2" /><rect x="2" y="2" width="11" height="6" rx="1" className="fill-current stroke-none" /><path d="M18.5 3.5v3" strokeLinecap="round" /></svg>
      </span>
    </div>
  )
}

function Lock({ party, message, lit }: { party: LegRole; message: PhoneMessage; lit: boolean }) {
  return (
    <div className={`absolute inset-0 ${PHONE_WALL[party]} transition-[filter] duration-700 ${lit ? '' : 'brightness-[0.62] saturate-[0.8]'}`}>
      <div className="flex h-full flex-col items-center pt-10 text-white">
        <p className="text-[2.75rem] leading-none font-light tracking-tight tabular-nums">{message.lockTime}</p>
        <p lang={message.lang} className="mt-1.5 text-xs font-medium text-white/80">{message.lockDate}</p>
      </div>
    </div>
  )
}

function Banner({ message }: { message: PhoneMessage }) {
  return (
    <div className="phone-banner absolute inset-x-2 top-7 z-20 flex items-center gap-2 rounded-2xl bg-white/95 px-2.5 py-2 text-[#10201b] shadow-lift">
      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-[#1fa66f] text-sm text-white"><Logo /></span>
      <div className="min-w-0">
        <p className="flex justify-between gap-1 text-[0.625rem] leading-tight font-bold"><span>G-One</span><span className="font-medium text-black/50">now</span></p>
        <p lang={message.lang} className="truncate text-[0.6875rem] leading-tight">{message.notice}</p>
      </div>
    </div>
  )
}

function Chat({ message, disclaimer }: { message: PhoneMessage; disclaimer: string }) {
  return (
    <div className="phone-chat absolute inset-0 z-10 flex flex-col bg-[#0d1a16] pt-5 text-white">
      <div className="flex items-center gap-2 bg-[#0f5a43] px-2.5 py-1.5">
        <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-[#1fa66f] text-xs"><Logo /></span>
        <div className="min-w-0 leading-tight">
          <p className="text-xs font-bold">G-One</p>
          <p className="truncate text-[0.5625rem] text-white/70">SMS / WhatsApp message</p>
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-hidden px-1.5 py-2">
        <div data-bubble="true" className="phone-bubble relative rounded-xl rounded-tl-sm bg-[#17503d] px-2 py-1.5 text-[0.6563rem] leading-snug shadow-card">
          <p className="text-[0.8125rem] leading-tight font-bold">{message.title}</p>
          <p lang={message.lang} className="text-[0.6563rem] leading-tight text-[#8fe3bf]">{message.subtitle}</p>
          <dl className="mt-1.5 space-y-0.5">
            {message.rows.map((row) => (
              <div key={row.label} className="grid grid-cols-[3.25rem_minmax(0,1fr)] gap-1">
                <dt lang={message.labelLang} className="text-[#a8cdbd]">{row.label}</dt>
                <dd lang={message.lang} className="font-semibold break-words">{row.value}</dd>
              </div>
            ))}
          </dl>
          <p lang={message.lang} className="mt-1.5 border-t border-white/15 pt-1 text-[0.625rem] leading-tight text-white/85">{message.note}</p>
          <p lang="en" className="mt-0.5 text-[0.5625rem] leading-tight text-white/65">{disclaimer}</p>
          <p aria-hidden="true" className="mt-0.5 text-right text-[0.5625rem] text-[#8fe3bf] tabular-nums">{message.lockTime} ✓✓</p>
        </div>
      </div>
    </div>
  )
}

function Caption({ lit }: { lit: boolean }) {
  return (
    <div className="flex h-12 flex-col items-center justify-start gap-0.5 pt-1.5 text-center">
      <p className="text-xs leading-tight font-semibold text-on-night-muted">Illustrative, nothing is sent</p>
      {lit && <a href={SAMPLE_RECEIPT_HREF} target="_blank" rel="noreferrer" title="Opens this sample's own receipt" className="text-xs font-semibold text-accent underline">View Sauda Parchi</a>}
    </div>
  )
}

/** One side's handset: asleep on its lock screen until the deal summary reaches it. */
export function Phone({ party, manifest, lit }: { party: LegRole; manifest: Manifest; lit: boolean }) {
  const message = manifest.phones[party]
  return (
    <figure data-phone={party} data-lit={lit} aria-label={`${party === 'farmer' ? "Farmer's" : "Buyer's"} phone`} className="m-0 w-[11rem] shrink-0">
      <div key={lit ? 'lit' : 'idle'} className={`relative h-[27.5rem] rounded-[1.9rem] bg-[#0b0d10] p-[0.3125rem] shadow-lift ring-1 ring-black/40 ${lit ? 'phone-buzz' : ''}`}>
        <div className="relative h-full overflow-hidden rounded-[1.6rem]">
          <span aria-hidden="true" className="absolute top-1 left-1/2 z-30 h-3 w-14 -translate-x-1/2 rounded-full bg-black" />
          <div className="absolute inset-x-0 top-0 z-30"><StatusBar time={message.lockTime} /></div>
          <Lock party={party} message={message} lit={lit} />
          {lit && <Banner message={message} />}
          {lit && <Chat message={message} disclaimer={manifest.disclaimer} />}
          <span aria-hidden="true" className="absolute bottom-1 left-1/2 z-30 h-1 w-16 -translate-x-1/2 rounded-full bg-white/70" />
        </div>
      </div>
      <figcaption><Caption lit={lit} /></figcaption>
    </figure>
  )
}
