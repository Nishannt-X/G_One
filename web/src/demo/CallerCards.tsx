import { callerCard } from '../lib/useSimulatorSetup'
import type { SimulatorConfig } from '../simulator/protocol'
import { CheckIcon } from '../ui/icons'

/** The value for a caller with no number: no memory is loaded or saved. */
export const WITHHELD = ''

interface Props {
  callers: SimulatorConfig['callers']
  value: string
  onChange: (number: string) => void
  disabled?: boolean
}

/** The demo farmers to call as, one card each, plus a withheld caller. A native radio group, so arrow keys move the choice. */
export default function CallerCards({ callers, value, onChange, disabled = false }: Props) {
  const options = [
    ...callers.map((c) => ({ value: c.number, ...callerCard(c.label) })),
    { value: WITHHELD, name: 'Withheld', detail: 'No memory', masked: null },
  ]
  return (
    <fieldset disabled={disabled} className="min-w-0">
      <legend className="sr-only">Demo farmer to call as</legend>
      <div role="radiogroup" aria-label="Demo farmer to call as" className="scrollbar-none -mx-1 flex snap-x gap-2 overflow-x-auto px-1 py-1 sm:mx-0 sm:grid sm:grid-cols-3 sm:overflow-visible sm:px-0">
        {options.map((o) => {
          const checked = o.value === value
          return (
            <label key={o.value || 'withheld'} className={`relative flex w-36 shrink-0 cursor-pointer snap-start flex-col rounded-xl border px-3 py-2 transition-colors has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus sm:w-auto ${checked ? 'border-brand bg-brand-soft' : 'border-line bg-surface hover:border-line-strong'}`}>
              <input type="radio" name="demo-caller" value={o.value} checked={checked} onChange={() => onChange(o.value)} className="sr-only" />
              <span className="flex items-center justify-between gap-2">
                <span className="truncate font-semibold">{o.name}</span>
                {checked && <CheckIcon aria-hidden="true" className="shrink-0 text-brand" />}
              </span>
              <span className="truncate text-sm text-muted">{[o.detail, o.masked].filter(Boolean).join(' · ') || 'Synthetic number'}</span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
