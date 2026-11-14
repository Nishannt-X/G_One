import { useEffect, useRef, type ComponentType, type SVGProps } from 'react'
import { ArrowIcon, BuyersIcon, CheckIcon, DoubleCheckIcon, EarIcon, HandshakeIcon, LanguageIcon, PhoneIcon, SendIcon, ShieldIcon, StoreIcon, WheatIcon } from '../ui/icons'
import { stepLanguages } from './languages'
import { clockAt, formatClock, statusOf, type Position, type Status } from './playback'
import { STAGES, type Scenario, type StepIcon } from './scenarios'

const ICONS: Record<StepIcon, ComponentType<SVGProps<SVGSVGElement>>> = {
  phone: PhoneIcon,
  language: LanguageIcon,
  ear: EarIcon,
  shield: ShieldIcon,
  handshake: HandshakeIcon,
  send: SendIcon,
  store: StoreIcon,
  wheat: WheatIcon,
  arrow: ArrowIcon,
  weigh: BuyersIcon,
  pay: DoubleCheckIcon,
}

const CHIP: Record<Status, { label: string; className: string }> = {
  done: { label: 'Done', className: 'badge badge-ok' },
  active: { label: 'Active', className: 'badge border-transparent bg-accent text-on-accent' },
  upcoming: { label: 'Upcoming', className: 'badge' },
}

/** The eight stages as a strip of pills, so the whole journey is visible at once. */
export function StageStrip({ scenario, at }: { scenario: Scenario; at: Position }) {
  return (
    <ol aria-label="Stages" className="flex flex-wrap gap-1.5">
      {STAGES.map((stage) => {
        const indexes = scenario.steps.flatMap((s, i) => (s.stage === stage.id ? [i] : []))
        const done = at.index > indexes[indexes.length - 1]
        const active = !done && at.index >= indexes[0]
        return (
          <li key={stage.id} className={`chip text-xs ${done ? 'bg-ok-soft text-ok' : active ? 'bg-accent text-on-accent' : 'bg-sunk text-muted'}`}>
            {done && <CheckIcon width="1em" height="1em" />}
            {stage.name}
          </li>
        )
      })}
    </ol>
  )
}

/** One row per step: name, scenario time, who, language, one plain line and a status chip. */
export default function Timeline({ scenario, at }: { scenario: Scenario; at: Position }) {
  const listRef = useRef<HTMLOListElement>(null)
  const active = Math.min(at.index, scenario.steps.length - 1)

  // Keep the active row in view inside the scrolling list, without scrolling the page on a phone.
  useEffect(() => {
    const list = listRef.current
    if (!list || list.scrollHeight <= list.clientHeight + 1) return
    const row = list.children[active] as HTMLElement | undefined
    if (row) list.scrollTo({ top: Math.max(0, row.offsetTop - list.offsetTop - 12), behavior: 'smooth' })
  }, [active])

  return (
    <ol ref={listRef} aria-label="Deal timeline" className="relative space-y-2 lg:max-h-[min(70vh,760px)] lg:overflow-y-auto lg:pr-1">
      {scenario.steps.map((step, i) => {
        const status = statusOf(i, at)
        const Icon = ICONS[step.icon]
        const chip = CHIP[status]
        const time = status === 'active' ? clockAt(scenario, at) : step.atMin
        return (
          <li
            key={step.id}
            aria-current={status === 'active' ? 'step' : undefined}
            className={`flex gap-3 rounded-xl border p-3 transition-colors duration-300 ${
              status === 'active' ? 'border-accent bg-accent-soft/60' : status === 'done' ? 'border-line bg-surface' : 'border-line/70 bg-page opacity-70'
            }`}
          >
            <span className={`mt-0.5 grid size-9 shrink-0 place-items-center rounded-full ${status === 'upcoming' ? 'bg-sunk text-faint' : status === 'active' ? 'bg-accent text-on-accent' : 'bg-brand-soft text-brand'}`}>
              <Icon />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
                <h3 className="font-bold leading-tight">{step.title}</h3>
                <span className={chip.className}>{chip.label}</span>
              </div>
              <p className="mt-0.5 text-sm text-muted">
                <span className="font-semibold tabular-nums">{formatClock(time)}</span> · {step.who} · {stepLanguages(step)}
              </p>
              <p className={`mt-1 text-[0.95rem] leading-snug ${step.id === 'price-ref' || step.id === 'caution' ? 'border-l-3 border-whisper-edge pl-2' : ''}`}>{step.line}</p>
            </div>
          </li>
        )
      })}
    </ol>
  )
}
