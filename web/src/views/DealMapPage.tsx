import { useState } from 'react'
import DealMap from '../deal-map/DealMap'
import { FREIGHT_SAMPLE_LABEL, SAMPLE_TRUCK, etaLabel } from '../deal-map/freight'
import { languageColour, languageName, type LanguageCode } from '../deal-map/languages'
import { locate, reached, type Position } from '../deal-map/playback'
import { rupees, SCENARIOS, type Scenario } from '../deal-map/scenarios'
import Timeline, { StageStrip } from '../deal-map/Timeline'
import { useDealPlayer, useReducedMotion, type Speed } from '../deal-map/useDealPlayer'
import { useTheme } from '../lib/theme'
import { AlertIcon, PauseIcon, PlayIcon, RestartIcon, SkipIcon } from '../ui/icons'
import { Segmented } from '../ui/parts'

export const SIMULATED_BANNER =
  'Simulated. Synthetic people, companies and prices. No real calls, tracking or payments. G-One coordinates and records agreed terms and guarantees no truck, price or payment.'

function Legend({ scenario }: { scenario: Scenario }) {
  const { theme } = useTheme()
  const codes = [...new Set<LanguageCode>([scenario.farmer.language, scenario.buyer.language, scenario.languageSwitch.to])]
  const item = 'flex items-center gap-2'
  return (
    <ul aria-label="Map legend" className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-muted">
      <li className={item}><span className="size-3 rounded-full bg-farmer" /> Farmer</li>
      <li className={item}><span className="size-3 rounded-full bg-buyer" /> Buyer</li>
      <li className={item}><span className="size-3 rounded-full bg-assistant ring-4 ring-accent/40" /> G-One, in the middle</li>
      <li className={item}><span className="size-3 rounded-full border-2 border-dashed border-whisper-edge" /> Private to the farmer</li>
      <li className={item}><span className="size-3 rounded-sm bg-accent ring-1 ring-ink" /> Truck, simulated</li>
      {codes.map((code) => (
        <li key={code} className={item}>
          <span className="h-1.5 w-5 rounded-full" style={{ background: languageColour(code, theme) }} /> {languageName(code)}
        </li>
      ))}
    </ul>
  )
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs font-bold tracking-wide text-faint uppercase">{label}</dt>
      <dd className="font-semibold">{children}</dd>
    </div>
  )
}

function DealFacts({ scenario, at }: { scenario: Scenario; at: Position }) {
  const locked = reached(scenario, at, 'lock', 0.5)
  const booked = reached(scenario, at, 'dispatch', 0.5)
  const { deal } = scenario
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <section aria-label="Agreed terms" className={`tile p-4 transition-opacity duration-500 ${locked ? '' : 'opacity-60'}`}>
        <h2 className="font-bold">Agreed terms</h2>
        {locked ? (
          <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-2">
            <Fact label="Crop">{deal.crop}</Fact>
            <Fact label="Quantity">{deal.quantityQuintals} quintal</Fact>
            <Fact label="Price">{rupees(deal.pricePerQuintal)} <span className="font-normal text-muted">{deal.basis}</span></Fact>
            <Fact label="Total">
              <span className="tabular-nums">{deal.quantityQuintals} × {rupees(deal.pricePerQuintal)} = {rupees(deal.total)}</span>
            </Fact>
          </dl>
        ) : (
          <p className="mt-2 text-sm text-muted">Not agreed yet. The terms appear when both sides say yes.</p>
        )}
      </section>
      <section aria-label="Logistics" className={`tile p-4 transition-opacity duration-500 ${booked ? '' : 'opacity-60'}`}>
        <h2 className="font-bold">Logistics, simulated</h2>
        {booked ? (
          <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-2">
            <Fact label="Transporter">{scenario.transporter.name}, speaks {languageName(scenario.transporter.language)}</Fact>
            <Fact label="ETA">{etaLabel(scenario.etaHours)}, about {scenario.km.toLocaleString('en-IN')} km</Fact>
            <Fact label="Freight, buyer pays">{rupees(scenario.freight)}</Fact>
            <Fact label="Cold storage">{reached(scenario, at, 'storage') ? `${scenario.storage.name}, an option because ${scenario.storage.reason}` : 'Not needed yet'}</Fact>
          </dl>
        ) : (
          <p className="mt-2 text-sm text-muted">No truck yet. G-One brings in a transporter after the deal locks.</p>
        )}
        <p className="mt-3 text-xs text-faint">
          Freight: part load of a {SAMPLE_TRUCK.capacityQuintals}-quintal {SAMPLE_TRUCK.en} at ₹{SAMPLE_TRUCK.rupeesPerKm} a km from the repo's freight table ({FREIGHT_SAMPLE_LABEL}). The distance and ETA are rough synthetic estimates.
        </p>
      </section>
    </div>
  )
}

const SPEEDS: readonly Speed[] = [1, 2, 4]

function Controls({ player }: { player: ReturnType<typeof useDealPlayer> }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {player.playing ? (
        <button type="button" className="btn-primary btn-sm" onClick={player.pause}><PauseIcon /> Pause</button>
      ) : (
        <button type="button" className="btn-primary btn-sm" onClick={player.play}><PlayIcon /> {player.ended ? 'Play again' : 'Play'}</button>
      )}
      <button type="button" className="btn-quiet btn-sm" onClick={player.step} disabled={player.ended}><SkipIcon /> Step</button>
      <button type="button" className="btn-quiet btn-sm" onClick={player.restart}><RestartIcon /> Restart</button>
      <div role="group" aria-label="Speed" className="ml-auto flex gap-0.5 rounded-lg bg-sunk p-0.5">
        {SPEEDS.map((speed) => (
          <button
            key={speed}
            type="button"
            aria-pressed={player.speed === speed}
            onClick={() => player.setSpeed(speed)}
            className={`min-h-8 cursor-pointer rounded-md px-2.5 text-sm font-semibold transition-colors ${player.speed === speed ? 'bg-surface text-ink shadow-card' : 'text-muted hover:text-ink'}`}
          >
            {speed}x
          </button>
        ))}
      </div>
    </div>
  )
}

function Player({ scenario }: { scenario: Scenario }) {
  const reduced = useReducedMotion()
  const { theme } = useTheme()
  const player = useDealPlayer(scenario.steps, !reduced)
  const raw = locate(scenario.steps, player.t)
  // With reduced motion each step shows its finished state, so nothing moves between steps.
  const at: Position = reduced ? { index: raw.index, fraction: 1 } : raw
  const current = scenario.steps[Math.min(at.index, scenario.steps.length - 1)]

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] lg:items-start">
      <div className="space-y-4">
        <div className="card overflow-hidden p-3 sm:p-4">
          <Controls player={player} />
          <div className="mt-2">
            <DealMap scenario={scenario} at={at} theme={theme} />
          </div>
          <div className="mt-3 border-t border-line pt-3">
            <Legend scenario={scenario} />
            <p className="mt-2 text-xs text-faint">Simplified outline of India drawn for this prototype, not to scale. Town positions are approximate.</p>
          </div>
        </div>
        <DealFacts scenario={scenario} at={at} />
      </div>
      <section aria-labelledby="timeline-title" className="card space-y-3 p-4 lg:sticky lg:top-20">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="timeline-title" className="text-lg font-bold">Timeline</h2>
          <span className="text-sm text-muted">{player.ended ? 'Played to the end' : `Now: ${current.title}`}</span>
        </div>
        <StageStrip scenario={scenario} at={at} />
        <p className="sr-only" aria-live="polite">{player.ended ? 'The simulated deal has played to the end.' : current.title}</p>
        <Timeline scenario={scenario} at={at} />
      </section>
    </div>
  )
}

/** The deal, end to end, on a map: a simulated prototype with static synthetic scenarios. */
export default function DealMapPage() {
  const [scenarioId, setScenarioId] = useState(SCENARIOS[0].id)
  const scenario = SCENARIOS.find((s) => s.id === scenarioId) ?? SCENARIOS[0]
  return (
    <div className="mx-auto max-w-[1440px] space-y-3 px-4 py-4 sm:px-6 md:py-5 lg:px-8">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <p className="kicker text-xs">Prototype</p>
          <h1 className="text-3xl font-bold md:text-4xl">Deal map</h1>
          <p className="max-w-2xl text-muted">One simulated deal, from the first ring to payment on delivery. {scenario.summary}</p>
        </div>
        <div className="w-full text-sm md:w-auto md:min-w-[28rem]">
          <Segmented label="Scenario" value={scenario.id} options={SCENARIOS.map((s) => ({ value: s.id, label: s.label }))} onChange={setScenarioId} />
        </div>
      </header>
      <p role="note" className="flex items-start gap-2 rounded-xl border border-whisper-edge bg-warn-soft px-3 py-2 text-sm font-semibold text-warn">
        <AlertIcon className="mt-0.5 shrink-0" />
        <span>{SIMULATED_BANNER}</span>
      </p>
      <Player key={scenario.id} scenario={scenario} />
    </div>
  )
}
