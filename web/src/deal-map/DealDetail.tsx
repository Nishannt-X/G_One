import { useEffect, type ReactNode } from 'react'
import { AlertIcon, ArrowIcon, PauseIcon, PhoneIcon, PlayIcon, RestartIcon, SkipIcon } from '../ui/icons'
import {
  difficultiesOf,
  formatStamp,
  G_ONE_NOT_STORED,
  G_ONE_RECORDS,
  journeyHours,
  kmOf,
  PAYER,
  PAYMENT,
  rupees,
  STOP_KIND,
  storageFeeOf,
  totalOf,
  type Deal,
  type Record_,
  type Stop,
} from './deals'
import { FREIGHT_SAMPLE_LABEL, SAMPLE_TRUCK } from './freight'
import { languageName } from './languages'
import { SPEEDS, type DemoView, type PlayerAction, type Speed } from './playback'
import StatusChip from './StatusChip'

export const DETAIL_TITLE_ID = 'deal-detail-title'
const stopEntryId = (i: number) => `deal-stop-${i}`

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs font-bold tracking-wide text-faint uppercase">{label}</dt>
      <dd className="font-semibold">{children}</dd>
    </div>
  )
}

function Records({ records }: { records: readonly Record_[] }) {
  if (!records.length) return <span className="text-muted">Nothing recorded here.</span>
  return (
    <ul className="space-y-0.5">
      {records.map((r) => (
        <li key={r.item}>
          {r.item} <span className="text-muted">({r.heldBy === 'G-One' ? "G-One's record" : `held by: ${r.heldBy}`})</span>
        </li>
      ))}
    </ul>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-0.5 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-3">
      <dt className="text-sm font-semibold text-faint">{label}</dt>
      <dd className="min-w-0 text-sm">{children}</dd>
    </div>
  )
}

function StopEntry({ stop, index, current }: { stop: Stop; index: number; current: boolean }) {
  const { difficulty } = stop
  return (
    <li id={stopEntryId(index)} className={`relative rounded-xl border p-3 pl-12 ${current ? 'border-accent bg-accent-soft/50' : 'border-line bg-surface'}`} aria-current={current ? 'step' : undefined}>
      <span
        aria-hidden="true"
        className={`absolute top-3 left-3 grid size-7 place-items-center rounded-full text-sm font-bold ${stop.planned ? 'border-2 border-dashed border-line-strong text-ink' : current ? 'bg-accent text-on-accent' : 'bg-brand text-on-brand'}`}
      >
        {index + 1}
      </span>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h3 className="font-bold"><span className="sr-only">Stop {index + 1}: </span>{STOP_KIND[stop.kind]}</h3>
        <span className="text-sm text-muted tabular-nums">
          {stop.planned ? 'Planned, estimate: ' : ''}
          {stop.arrive && `${stop.planned ? 'arrive' : 'Arrived'} ${formatStamp(stop.arrive)}`}
          {stop.arrive && stop.depart && ' · '}
          {stop.depart && `${stop.planned ? 'leave' : 'Left'} ${formatStamp(stop.depart)}`}
          {stop.arrive && !stop.depart && !stop.planned && ' · still here'}
        </span>
      </div>
      <p className="text-sm text-muted">{stop.place}{stop.km > 0 && `, about ${stop.km.toLocaleString('en-IN')} km from the previous stop`}</p>
      <dl className="mt-2 space-y-1.5">
        <Row label="Who handles">{stop.handler.role}: {stop.handler.name}</Row>
        <Row label="Hand-off">{stop.handoff}</Row>
        <Row label="Recorded">
          <Records records={stop.records} />
        </Row>
        {stop.costs && stop.costs.length > 0 && (
          <Row label="Costs here">
            <ul className="space-y-0.5">
              {stop.costs.map((c) => (
                <li key={c.label} className="tabular-nums">
                  {c.label}: {rupees(c.rupees)}, paid by the {PAYER[c.paidBy]}{c.inFreight ? ' (inside the freight)' : ''}
                </li>
              ))}
            </ul>
          </Row>
        )}
      </dl>
      {difficulty && (
        <div role="note" className="mt-3 rounded-lg border border-whisper-edge bg-warn-soft p-3 text-sm text-ink">
          <p className="flex items-center gap-2 font-bold text-warn">
            <AlertIcon className="shrink-0" /> Difficulty, about {difficulty.delayHours} {difficulty.delayHours === 1 ? 'hour' : 'hours'} lost
          </p>
          <dl className="mt-1.5 space-y-1">
            <Row label="What happened">{difficulty.cause}</Row>
            <Row label="Who was told">{difficulty.told}</Row>
            <Row label="How resolved">{difficulty.resolution}</Row>
            <Row label="Cost impact">{difficulty.costImpact}</Row>
          </dl>
        </div>
      )}
    </li>
  )
}

function CallEntry({ deal }: { deal: Deal }) {
  return (
    <li className="relative rounded-xl border border-line bg-sunk/60 p-3 pl-12">
      <span aria-hidden="true" className="absolute top-3 left-3 grid size-7 place-items-center rounded-full bg-assistant text-on-brand">
        <PhoneIcon className="size-4" />
      </span>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h3 className="font-bold">Deal agreed on a call</h3>
        <span className="text-sm text-muted tabular-nums">{formatStamp(deal.call.at)} · {deal.call.minutes} min</span>
      </div>
      <p className="text-sm text-muted">{languageName(deal.farmer.language)} ↔ {languageName(deal.buyer.language)}. {deal.call.note}</p>
      <dl className="mt-2 space-y-1.5">
        <Row label="Recorded">
          <Records records={G_ONE_RECORDS} />
          <p className="mt-0.5 text-muted">{G_ONE_NOT_STORED}</p>
        </Row>
      </dl>
    </li>
  )
}

function Summary({ deal }: { deal: Deal }) {
  const hours = journeyHours(deal)
  const storage = storageFeeOf(deal)
  const storagePayers = [...new Set(deal.stops.flatMap((s) => s.costs ?? []).filter((c) => c.storage).map((c) => PAYER[c.paidBy]))]
  const trouble = difficultiesOf(deal)
  const cell = 'min-w-0 rounded-lg bg-sunk px-3 py-2 [&>dd:first-of-type]:text-[15px] [&>dd:first-of-type]:leading-snug'
  return (
    <dl aria-label="Journey summary" className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      <div className={cell}><dt className="text-xs font-semibold text-faint">Road distance</dt><dd className="font-bold tabular-nums">about {kmOf(deal).toLocaleString('en-IN')} km</dd></div>
      <div className={cell}><dt className="text-xs font-semibold text-faint">Time on the road</dt><dd className="font-bold tabular-nums">{hours === null ? 'Not started' : `${hours} hours${deal.stops.some((s) => s.planned) ? ' so far' : ''}`}</dd></div>
      <div className={cell}><dt className="text-xs font-semibold text-faint">Freight, estimate</dt><dd className="font-bold tabular-nums">{rupees(deal.freight.rupees)}</dd><dd className="text-xs text-muted">paid by the {PAYER[deal.freight.paidBy]}</dd></div>
      <div className={cell}><dt className="text-xs font-semibold text-faint">Storage fee</dt><dd className="font-bold tabular-nums">{storage ? rupees(storage) : 'None'}</dd>{storage > 0 && <dd className="text-xs text-muted">paid by the {storagePayers.join(', ')}</dd>}</div>
      <div className={cell}><dt className="text-xs font-semibold text-faint">Shrinkage or loss</dt><dd className="font-bold">{deal.loss ? `${deal.loss.quintals} quintal` : 'None'}</dd>{deal.loss && <dd className="text-xs text-muted">borne by the {PAYER[deal.loss.borneBy]}</dd>}</div>
      <div className={`${cell} ${trouble ? 'bg-warn-soft' : ''}`}><dt className="text-xs font-semibold text-faint">Difficulties</dt><dd className={`font-bold ${trouble ? 'text-warn' : ''}`}>{trouble}</dd></div>
    </dl>
  )
}

export interface PlayerProps {
  playing: boolean
  speed: Speed
  started: boolean
  send: (action: PlayerAction) => void
}

function DemoControls({ player, demo }: { player: PlayerProps; demo: DemoView | null }) {
  const ended = demo?.ended ?? false
  return (
    <div className="rounded-xl border border-line bg-sunk/60 p-3">
      <div className="flex flex-wrap items-center gap-1.5">
        {player.playing ? (
          <button type="button" className="btn-primary btn-sm" onClick={() => player.send({ type: 'pause' })}><PauseIcon /> Pause</button>
        ) : (
          <button type="button" className="btn-primary btn-sm" onClick={() => player.send({ type: 'play' })}>
            <PlayIcon /> {ended ? 'Play again' : player.started ? 'Resume' : 'Play the journey'}
          </button>
        )}
        <button type="button" className="btn-quiet btn-sm" onClick={() => player.send({ type: 'step' })}><SkipIcon /> Step</button>
        <button type="button" className="btn-quiet btn-sm" onClick={() => player.send({ type: 'restart' })} disabled={!player.started}><RestartIcon /> Restart</button>
        <div role="group" aria-label="Speed" className="ml-auto flex gap-0.5 rounded-lg bg-surface p-0.5">
          {SPEEDS.map((speed) => (
            <button
              key={speed}
              type="button"
              aria-pressed={player.speed === speed}
              onClick={() => player.send({ type: 'speed', speed })}
              className={`min-h-8 cursor-pointer rounded-md px-2.5 text-sm font-semibold transition-colors ${player.speed === speed ? 'bg-brand text-on-brand' : 'text-muted hover:text-ink'}`}
            >
              {speed}x
            </button>
          ))}
        </div>
      </div>
      <p className="mt-2 text-sm text-muted" aria-live="polite">
        {demo ? <>Now: <span className="font-semibold text-ink">{demo.title}</span></> : 'Play walks the truck from stop to stop and reveals each hand-off in order.'}
      </p>
    </div>
  )
}

/** One deal's full logistics story, stop by stop. */
export default function DealDetail({ deal, demo, player, onBack }: { deal: Deal; demo: DemoView | null; player: PlayerProps; onBack: () => void }) {
  const shown = demo ? demo.reached : deal.stops.length
  const current = demo?.current ?? null

  useEffect(() => {
    // Keep the stop being played in sight inside the sidebar; on a phone the page itself would jump, so leave it.
    if (current === null || !window.matchMedia('(min-width: 1024px)').matches) return
    const entry = document.getElementById(stopEntryId(current))
    const box = entry?.closest('aside')
    if (!entry || !box) return
    // Scroll only the sidebar, never the page.
    const e = entry.getBoundingClientRect()
    const b = box.getBoundingClientRect()
    if (e.top < b.top) box.scrollTop -= b.top - e.top + 8
    else if (e.bottom > b.bottom) box.scrollTop += Math.min(e.bottom - b.bottom + 8, e.top - b.top - 8)
  }, [current])

  return (
    <article aria-labelledby={DETAIL_TITLE_ID} className="space-y-4 p-4">
      <button type="button" className="btn-ghost btn-sm -ml-2" onClick={onBack}>
        <ArrowIcon className="rotate-180" /> All deals
      </button>
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <StatusChip status={demo ? demo.status : deal.status} />
          {deal.demo && <span className="badge badge-brand">Demo</span>}
        </div>
        <h2 id={DETAIL_TITLE_ID} tabIndex={-1} className="text-2xl font-bold outline-none">
          {deal.crop}, {deal.lane.from} → {deal.lane.to}
        </h2>
      </header>

      {deal.demo && <DemoControls player={player} demo={demo} />}

      <dl className="grid grid-cols-2 gap-x-3 gap-y-3">
        <Fact label="Quantity">{deal.quantity.amount} {deal.quantity.unit}{deal.quantity.unit !== 'quintal' && <span className="font-normal text-muted"> ({deal.quantity.quintals} quintal)</span>}</Fact>
        <Fact label="Price">{rupees(deal.price.rupees)} per {deal.price.per}</Fact>
        <div className="col-span-2 -mt-1 text-sm text-muted">Basis: {deal.price.basis}</div>
        <Fact label="Total"><span className="tabular-nums">{deal.quantity.amount} × {rupees(deal.price.rupees)} = {rupees(totalOf(deal))}</span></Fact>
        <Fact label="Payment, simulated">{PAYMENT[deal.payment.state]}: {rupees(deal.payment.rupees)}<span className="block text-sm font-normal text-muted">{deal.payment.method}. {deal.payment.note}</span></Fact>
        <Fact label="Farmer">{deal.farmer.name}<span className="block text-sm font-normal text-muted">{deal.farmer.village}, speaks {languageName(deal.farmer.language)}</span></Fact>
        <Fact label="Buyer">{deal.buyer.business}<span className="block text-sm font-normal text-muted">{deal.buyer.contact}, {deal.buyer.city}, speaks {languageName(deal.buyer.language)}</span></Fact>
      </dl>

      <Summary deal={deal} />

      <section aria-labelledby="journey-title">
        <h3 id="journey-title" className="mb-2 text-lg font-bold">The journey, stop by stop</h3>
        <ol className="space-y-2">
          <CallEntry deal={deal} />
          {deal.stops.slice(0, shown).map((stop, i) => (
            <StopEntry key={i} stop={stop} index={i} current={current === i} />
          ))}
        </ol>
        {shown < deal.stops.length && (
          <p className="mt-2 text-sm text-muted">{deal.stops.length - shown} more {deal.stops.length - shown === 1 ? 'stop' : 'stops'} to come as the demo plays.</p>
        )}
      </section>

      <p className="text-xs text-faint">
        Freight is an estimate: the share of a {SAMPLE_TRUCK.capacityQuintals}-quintal {SAMPLE_TRUCK.en} at ₹{SAMPLE_TRUCK.rupeesPerKm} a km from the repo's freight table ({FREIGHT_SAMPLE_LABEL}). Distances and times are rough estimates. Slips, receipts and logs belong to whoever is named as holding them; G-One keeps only the deal card and call logs.
      </p>
    </article>
  )
}
