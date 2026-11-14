import { useEffect, useMemo, useReducer, useState } from 'react'
import { browse, listItemId } from '../deal-map/browse'
import DealDetail, { DETAIL_TITLE_ID } from '../deal-map/DealDetail'
import DealList from '../deal-map/DealList'
import DealMap from '../deal-map/DealMap'
import { dealById, DEALS } from '../deal-map/deals'
import { MAP_SOURCE } from '../deal-map/indiaMapData'
import { demoSteps, demoView, displayPosition, locate } from '../deal-map/playback'
import { useDealPlayer, useReducedMotion } from '../deal-map/useDealPlayer'
import { AlertIcon } from '../ui/icons'

export const SIMULATED_BANNER =
  'Simulated. These are made-up example deals, not real transactions, transporters or payments. Freight, distances and arrival times are estimates. G-One coordinates and records agreed terms; it guarantees no truck, price or payment.'

function Legend() {
  const item = 'flex items-center gap-1.5'
  return (
    <ul aria-label="Map legend" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
      <li className={item}><span className="grid size-4 place-items-center rounded-full bg-brand text-[9px] font-bold text-on-brand">1</span> Stop done</li>
      <li className={item}><span className="size-4 rounded-full border-2 border-dashed border-line-strong" /> Planned</li>
      <li className={item}><span className="size-4 rounded-full bg-accent" /> Now, in the demo</li>
      <li className={item}><span className="grid size-3.5 place-items-center rounded-full bg-warn text-[9px] font-bold text-warn-soft">!</span> Difficulty</li>
      <li className={item}><span className="size-2.5 rounded-full bg-farmer" /> Origin</li>
      <li className={item}><span className="size-2.5 rounded-sm bg-buyer" /> Destination</li>
    </ul>
  )
}

/** Every simulated deal on a map of India, and each one's logistics story stop by stop. */
export default function DealMapPage({ initialDealId = null }: { initialDealId?: string | null }) {
  const reduced = useReducedMotion()
  const [state, dispatch] = useReducer(browse, { selected: initialDealId, lastOpened: initialDealId })
  const [highlight, setHighlight] = useState<string | null>(null)
  const selected = dealById(state.selected)
  const steps = useMemo(() => (selected?.demo ? demoSteps(selected) : []), [selected])
  const player = useDealPlayer(steps)
  const demo = selected?.demo && player.started ? demoView(selected, steps, displayPosition(locate(steps, player.t), reduced)) : null

  // Move focus with the view: into the detail when a deal opens, back to its list item when the list returns.
  useEffect(() => {
    const target = state.selected ? DETAIL_TITLE_ID : state.lastOpened ? listItemId(state.lastOpened) : null
    if (target) document.getElementById(target)?.focus({ preventScroll: true })
  }, [state.selected, state.lastOpened])

  const select = (id: string) => {
    player.send({ type: 'reset' })
    setHighlight(null)
    dispatch({ type: 'select', id })
    // On a phone the list sits below the map: bring the map back into sight so the route is seen.
    if (!window.matchMedia('(min-width: 1024px)').matches) document.getElementById('deal-map-card')?.scrollIntoView({ block: 'start', behavior: reduced ? 'auto' : 'smooth' })
  }
  const back = () => {
    player.send({ type: 'reset' })
    dispatch({ type: 'back' })
  }

  return (
    <div className="mx-auto max-w-[1440px] space-y-3 px-4 py-4 sm:px-6 md:py-5 lg:px-8">
      <header className="min-w-0">
        <p className="kicker text-xs">Prototype</p>
        <h1 className="text-3xl font-bold">Deal map</h1>
        <p className="max-w-4xl text-muted">
          Ten example deals made on G-One calls, and what happened after: every stop on the road, who handled the load, how each hand-off went and what got in the way. Two are demos you can play.
        </p>
      </header>
      <p role="note" className="flex items-start gap-2 rounded-xl border border-whisper-edge bg-warn-soft px-3 py-2 text-sm font-semibold text-warn">
        <AlertIcon className="mt-0.5 shrink-0" />
        <span>{SIMULATED_BANNER}</span>
      </p>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(360px,420px)] lg:items-start">
        <div id="deal-map-card" className="card flex scroll-mt-20 flex-col overflow-hidden lg:sticky lg:top-20">
          <div className="h-[56vh] max-h-[560px] min-h-[300px] lg:h-[calc(100dvh-21rem)] lg:max-h-none lg:min-h-[400px]">
            <DealMap deals={DEALS} selected={selected} highlight={highlight} demo={demo} reduced={reduced} />
          </div>
          <div className="space-y-1 border-t border-line px-3 py-2">
            <Legend />
            <p className="text-xs text-faint">Map: {MAP_SOURCE}. Not a survey map; town positions and roads are approximate.</p>
          </div>
        </div>
        <aside aria-label="Deals" className="card min-w-0 overflow-hidden lg:sticky lg:top-20 lg:h-[calc(100dvh-16.5rem)] lg:min-h-[470px] lg:overflow-y-auto">
          {selected ? (
            <DealDetail key={selected.id} deal={selected} demo={demo} player={player} onBack={back} />
          ) : (
            <DealList deals={DEALS} lastOpened={state.lastOpened} onSelect={select} onHighlight={setHighlight} />
          )}
        </aside>
      </div>
    </div>
  )
}
