import type { LegRole } from '../../types/console'
import { EarIcon, EarOffIcon, MicIcon } from '../../ui/icons'
import type { SideNow } from './sides'

const LIT = {
  farmer: 'bg-farmer text-page',
  buyer: 'bg-buyer text-page',
}

/** Lit when this side hears the line, hatched when nothing reaches them. */
export function EarChip({ party, now, compact = false }: { party: LegRole; now: SideNow; compact?: boolean }) {
  const { hearing, label } = now
  const tone =
    hearing === 'hears' ? LIT[party] :
    hearing === 'speaking' ? 'bg-sunk text-ink' :
    hearing === 'silent' ? 'hatch-quiet bg-surface text-faint' : 'bg-sunk text-faint'
  return (
    <span title={label} data-hearing={hearing ?? 'idle'} data-party={party} className={`inline-flex h-7 max-w-full shrink-0 items-center gap-1.5 rounded-full px-2.5 text-sm font-bold whitespace-nowrap transition-colors duration-200 ${tone}`}>
      {hearing === 'speaking' ? <MicIcon /> : hearing === 'silent' ? <EarOffIcon /> : <EarIcon />}
      <span className={compact ? 'sr-only' : 'truncate'}>{label}</span>
    </span>
  )
}
