import { difficultiesOf, formatStamp, rupees, totalOf, type Deal } from './deals'
import { listItemId } from './browse'
import StatusChip from './StatusChip'

export default function DealList({ deals, lastOpened, onSelect, onHighlight }: { deals: readonly Deal[]; lastOpened: string | null; onSelect: (id: string) => void; onHighlight: (id: string | null) => void }) {
  return (
    <section aria-labelledby="deal-list-title" className="flex flex-col">
      <header className="border-b border-line px-4 py-3">
        <h2 id="deal-list-title" className="text-lg font-bold">All deals <span className="font-semibold text-muted">({deals.length})</span></h2>
        <p className="text-sm text-muted">Pick a deal to see every stop, who handled it and what went wrong on the way.</p>
      </header>
      <ul className="divide-y divide-line" onMouseLeave={() => onHighlight(null)}>
        {deals.map((deal) => {
          const trouble = difficultiesOf(deal)
          return (
            <li key={deal.id}>
              <button
                id={listItemId(deal.id)}
                type="button"
                aria-current={lastOpened === deal.id ? 'true' : undefined}
                onClick={() => onSelect(deal.id)}
                onMouseEnter={() => onHighlight(deal.id)}
                onFocus={() => onHighlight(deal.id)}
                onBlur={() => onHighlight(null)}
                className={`block w-full cursor-pointer px-4 py-3 text-left transition-colors hover:bg-sunk focus-visible:outline-3 focus-visible:-outline-offset-3 focus-visible:outline-focus ${lastOpened === deal.id ? 'bg-sunk/60' : ''}`}
              >
                <span className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                  <span className="font-bold">
                    {deal.crop}, {deal.lane.from} → {deal.lane.to}
                  </span>
                  <StatusChip status={deal.status} />
                </span>
                <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted">
                  <span className="tabular-nums">{deal.quantity.amount} {deal.quantity.unit} · {rupees(totalOf(deal))}</span>
                  <span>Agreed {formatStamp(deal.call.at)}</span>
                  <span>{deal.stops.length} stops</span>
                  {trouble > 0 && <span className="font-semibold text-warn">{trouble} {trouble === 1 ? 'difficulty' : 'difficulties'}</span>}
                  {deal.demo && <span className="badge badge-brand">Demo, can play</span>}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
