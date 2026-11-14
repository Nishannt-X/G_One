import { STATUS, type DealStatus, type Tone } from './deals'

const TONE: Record<Tone, string> = {
  ok: 'bg-ok-soft text-ok',
  info: 'bg-buyer-soft text-buyer',
  warn: 'bg-warn-soft text-warn',
  bad: 'bg-bad-soft text-bad',
}

export default function StatusChip({ status }: { status: DealStatus }) {
  const { label, tone } = STATUS[status]
  return <span className={`chip text-xs ${TONE[tone]}`} data-status={status}>{label}</span>
}
