import type { CallView } from '../lib/callState'
import { dealPrice, humanize, rupees, shortDate } from '../lib/format'
import { dealValue, meterPosition, METER_RANGE_PCT, priceCheck } from '../lib/pricing'
import type { DealState } from '../types/console'
import { CheckIcon, CrossIcon } from '../ui/icons'
import MemoryPanel from './MemoryPanel'
import type { SavedGist } from './savedGist'

const STEPS: { state: DealState; label: string }[] = [
  { state: 'draft', label: 'Draft' },
  { state: 'readback', label: 'Read-back' },
  { state: 'confirming', label: 'Confirming' },
  { state: 'agreed', label: 'Agreed' },
]

function Term({ label, value, wide, compact }: { label: string; value: string | null; wide?: boolean; compact?: boolean }) {
  return (
    <div className={`min-w-0 rounded-xl bg-sunk ${compact ? 'px-2.5 py-1.5' : 'px-3 py-2'} ${wide ? 'col-span-2' : ''}`}>
      <dt className={`${compact ? 'text-xs' : 'text-sm'} font-medium text-muted`}>{label}</dt>
      <dd key={value ?? ''} title={value ?? undefined} className={`-mx-1 truncate rounded px-1 ${compact ? 'text-base' : 'text-xl'} leading-snug font-semibold ${value ? 'animate-fill' : 'text-faint'}`}>{value ?? '—'}</dd>
    </div>
  )
}

function Yes({ who, yes, tone, compact }: { who: string; yes: boolean; tone: string; compact?: boolean }) {
  return (
    <p className={`flex min-w-0 items-center gap-1.5 rounded-xl border px-2.5 font-semibold transition-colors duration-300 ${compact ? 'py-1.5 text-sm' : 'py-2 text-base'} ${yes ? `border-transparent ${tone}` : 'border-line text-muted'}`}>
      {yes ? <CheckIcon /> : <span aria-hidden="true" className="inline-block h-[1.25em] w-[1.25em] shrink-0 rounded-full border-2 border-dashed border-current" />}
      <span className="truncate">{who}{compact ? (yes ? ' said yes' : <span className="sr-only">: not yet</span>) : `: ${yes ? 'yes' : 'not yet'}`}</span>
    </p>
  )
}

function Meter({ call }: { call: CallView | null }) {
  const { reference, comparison, delta, verdict } = priceCheck(call)
  return (
    <section aria-label="Price check" className="border-t border-line pt-4">
      <h3 className="font-semibold">Price check</h3>
      <div role="img" aria-label={verdict ?? 'No offer to compare yet'} className="relative mt-1 h-10 pt-6">
        <div className="h-2.5 rounded-full" style={{ background: 'linear-gradient(90deg, var(--bad), var(--whisper-edge) 42%, var(--ok) 62%, var(--ok))', opacity: comparison ? 1 : 0.35 }} />
        <div aria-hidden="true" className="absolute top-5 left-1/2 h-[1.125rem] w-0.5 -translate-x-1/2 bg-ink" />
        {comparison && (
          <div aria-hidden="true" className="pointer-events-none absolute top-0 -translate-x-1/2 transition-[left] duration-500" style={{ left: `${meterPosition(delta)}%` }}>
            <div className="w-fit text-center">
              <span className="block rounded bg-ink px-1.5 text-xs leading-[1.125rem] font-bold text-page tabular-nums">{delta > 0 ? '+' : ''}{delta.toFixed(1)}%</span>
              <span className="mx-auto block h-0 w-0 border-x-4 border-t-[6px] border-x-transparent border-t-ink" />
            </div>
          </div>
        )}
      </div>
      <div className="mt-1 flex justify-between text-xs text-muted tabular-nums">
        <span>−{METER_RANGE_PCT}%</span><span>Dated reference</span><span>+{METER_RANGE_PCT}%</span>
      </div>
      <div className="mt-1.5 min-h-[3.25rem] text-[0.9375rem] leading-snug">
        {comparison && reference ? (
          <>
            <p className="font-semibold">{verdict}</p>
            <p className="text-muted">Offer {rupees(comparison.offerPerQuintal)} vs {rupees(comparison.referencePerQuintal)} a quintal{reference.date ? `, dated ${reference.date}` : ''}. Not today's price.</p>
          </>
        ) : (
          <p className="text-muted">{reference ? 'Waiting for an offer to compare.' : 'Waiting for a mandi reference and an offer.'}</p>
        )}
      </div>
    </section>
  )
}

function Remembered({ call }: { call: CallView | null }) {
  const memory = call?.memory ?? null
  const facts = memory?.facts.slice(0, 4) ?? []
  const gist = memory?.gists[0]
  return (
    <section aria-label="What G-One remembered" className="border-t border-line pt-4">
      <h3 className="font-semibold">What G-One remembered</h3>
      <div className="mt-1.5 min-h-[4.5rem] text-[0.9375rem] leading-snug">
        {!memory ? (
          <p className="text-muted">Loaded at the start of the call.</p>
        ) : !memory.returning && facts.length === 0 ? (
          <p className="text-muted">First call from this number. Nothing on file yet.</p>
        ) : (
          <>
            <div className="flex flex-wrap gap-1.5">
              {facts.map((f) => (
                <span key={f.field} className="badge" title={f.provenance.saidOrInferred === 'said' ? 'The farmer said this' : 'The assistant inferred this'}>
                  <span className="font-medium text-faint capitalize">{humanize(f.field)}</span> {f.value}
                </span>
              ))}
            </div>
            {gist && <p key={gist.callId} className="animate-fill mt-2 rounded text-muted"><span className="font-semibold text-ink">{shortDate(gist.startedMs)}:</span> {gist.summary}</p>}
          </>
        )}
      </div>
    </section>
  )
}

/** The price check as one quiet line, for the working call page. */
function PriceLine({ call }: { call: CallView | null }) {
  const { reference, comparison, verdict } = priceCheck(call)
  if (!comparison || !reference) return null
  return (
    <p className="border-t border-line pt-3 text-sm leading-snug">
      <span className="font-semibold">Price check: {verdict}.</span>{' '}
      <span className="text-muted">Offer {rupees(comparison.offerPerQuintal)} vs {rupees(comparison.referencePerQuintal)} a quintal{reference.date ? `, dated ${reference.date}` : ''}. Not today's price.</span>
    </p>
  )
}

/**
 * The deal as it stands. Every slot is there from the start, so nothing moves while the terms fill in. The compact
 * form, for the working call page, shows the price check as one line and the live memory panel with its saved note.
 */
export default function DealPanel({ call, compact = false, saved = null }: { call: CallView | null; compact?: boolean; saved?: SavedGist | null }) {
  const deal = call?.deal ?? null
  const closed = deal?.state === 'closed'
  const reached = deal ? STEPS.findIndex((s) => s.state === deal.state) : -1
  const total = deal ? dealValue(deal.quantity, deal.price, deal.unit) : null
  const agreed = deal?.state === 'agreed'
  return (
    <aside aria-label="Deal" className={`card flex flex-col p-4 ${compact ? 'gap-3' : 'gap-4 lg:h-full lg:overflow-y-auto'}`}>
      <header className="flex items-center justify-between gap-2">
        <h2 className={compact ? 'text-lg font-semibold' : 'text-2xl font-semibold'}>The deal</h2>
        {agreed ? <span className="chip bg-ok-soft text-ok"><CheckIcon /> Agreed</span> : closed ? <span className="chip bg-bad-soft text-bad"><CrossIcon /> Closed</span> : <span className="chip bg-sunk text-muted">{deal ? 'In progress' : 'No deal yet'}</span>}
      </header>
      <ol aria-label="Deal progress" className="grid grid-cols-4 gap-1">
        {STEPS.map((s, i) => (
          <li key={s.state} aria-current={i === reached ? 'step' : undefined}>
            <div className={`h-1.5 rounded-full transition-colors duration-500 ${!closed && i <= reached ? 'bg-ok' : 'bg-sunk'}`} />
            <p className={`mt-1 text-[0.8125rem] leading-tight ${!closed && i === reached ? 'font-bold text-ink' : 'text-muted'}`}>{s.label}</p>
          </li>
        ))}
      </ol>
      <dl className="grid grid-cols-2 gap-2">
        <Term compact={compact} label="Crop" value={deal ? [deal.variety, deal.commodity].filter(Boolean).join(' ') || null : null} />
        <Term compact={compact} label="Quantity" value={deal?.quantity ?? null} />
        <Term compact={compact} label="Price" value={deal?.price != null ? dealPrice(deal.price, deal.unit) : null} />
        <Term compact={compact} label="Total" value={total !== null ? rupees(total) : null} />
        <Term compact={compact} label="Pickup" value={deal?.pickup ?? null} wide />
      </dl>
      <div className="grid grid-cols-2 gap-2">
        <Yes compact={compact} who="Farmer" yes={deal?.farmerYes ?? false} tone="bg-farmer-soft text-farmer" />
        <Yes compact={compact} who="Buyer" yes={deal?.buyerYes ?? false} tone="bg-buyer-soft text-buyer" />
      </div>
      <p className="-mt-1 text-sm text-faint">
        A record of what was said on the call, not a contract.
        {agreed && call?.origin === 'feed' && <> <a className="font-semibold text-brand underline" href={`/api/deals/${encodeURIComponent(call.callId)}/receipt.html`} target="_blank" rel="noreferrer">Open the receipt</a></>}
      </p>
      {compact ? <><PriceLine call={call} /><MemoryPanel call={call} saved={saved} /></> : <><Meter call={call} /><Remembered call={call} /></>}
    </aside>
  )
}
