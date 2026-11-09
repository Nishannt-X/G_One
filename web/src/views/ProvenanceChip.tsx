import { useT } from '../lib/i18n'
import type { Origin } from '../types/console'

/** Whether the farmer said this or the assistant inferred it. Worded, never colour alone. */
export function ProvenanceChip({ origin }: { origin: Origin }) {
  const t = useT()
  return <span className={`chip ${origin === 'said' ? 'bg-farmer-soft text-farmer' : 'bg-assistant-soft text-assistant'}`}>{t(origin === 'said' ? 'prov_said' : 'prov_inferred')}</span>
}
