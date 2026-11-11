import type { CallView } from '../lib/callState'
import { rupees } from '../lib/format'
import { useI18n, type MessageKey } from '../lib/i18n'
import { dealValue } from '../lib/pricing'
import type { DealState } from '../types/console'
import { CheckIcon, CrossIcon } from '../ui/icons'
import { Card } from '../ui/parts'

const STEPS: { state: DealState; label: MessageKey }[] = [
  { state: 'draft', label: 'deal_step_heard' },
  { state: 'readback', label: 'deal_step_readback' },
  { state: 'confirming', label: 'deal_step_yes' },
  { state: 'agreed', label: 'deal_step_agreed' },
]

function Term({ label, value }: { label: string; value: string | null }) {
  // Keyed on the value, so the highlight replays exactly when a term is filled in or changes.
  return (
    <div className="min-w-0 rounded-lg bg-sunk px-3 py-2">
      <dt className="text-xs font-semibold text-muted">{label}</dt>
      <dd key={value ?? ''} className={`-mx-1 truncate rounded px-1 text-lg font-bold ${value ? 'animate-fill' : 'text-faint'}`} title={value ?? undefined}>{value ?? '—'}</dd>
    </div>
  )
}

function Yes({ label, yes }: { label: string; yes: boolean }) {
  const { t } = useI18n()
  return (
    <p className={`flex items-center gap-1.5 font-semibold ${yes ? 'text-ok' : 'text-muted'}`}>
      {yes ? <CheckIcon /> : <span aria-hidden="true" className="inline-block h-[1.25em] w-[1.25em] rounded-full border-2 border-dashed border-current" />}
      {label}: <span>{t(yes ? 'said_yes' : 'not_yet')}</span>
    </p>
  )
}

/** The deal summary, filling in as the terms are spoken. The slots are always there, so nothing jumps. */
export default function DealCard({ call }: { call: CallView }) {
  const { t } = useI18n()
  const deal = call.deal
  const closed = deal?.state === 'closed'
  const reached = deal ? STEPS.findIndex((s) => s.state === deal.state) : -1
  const total = deal ? dealValue(deal.quantity, deal.price, deal.unit) : null
  return (
    <Card
      title={t('deal_title')}
      aside={deal?.state === 'agreed' ? <span className="chip bg-ok-soft text-ok"><CheckIcon /> {t('deal_agreed')}</span> : closed ? <span className="chip bg-bad-soft text-bad"><CrossIcon /> {t('deal_closed')}</span> : undefined}
    >
      <ol className="mb-3 grid grid-cols-4 gap-1" aria-label={t('deal_progress')}>
        {STEPS.map((step, i) => (
          <li key={step.state} aria-current={i === reached ? 'step' : undefined}>
            <div className={`h-1.5 rounded-full transition-colors duration-500 ${!closed && i <= reached ? 'bg-ok' : 'bg-sunk'}`} />
            <p className={`mt-1 text-xs leading-tight ${!closed && i === reached ? 'font-bold text-ink' : 'text-muted'}`}>{t(step.label)}</p>
          </li>
        ))}
      </ol>
      <dl className="grid grid-cols-2 gap-2">
        <Term label={t('term_crop')} value={deal ? [deal.variety, deal.commodity].filter(Boolean).join(' ') || null : null} />
        <Term label={t('term_quantity')} value={deal?.quantity ?? null} />
        <Term label={t('term_price')} value={deal?.price != null ? `${rupees(deal.price)}${deal.unit ? ` / ${deal.unit}` : ''}` : null} />
        <Term label={t('term_value')} value={total !== null ? rupees(total) : null} />
        <Term label={t('term_market')} value={deal?.market ?? null} />
        <Term label={t('term_pickup')} value={deal?.pickup ?? null} />
      </dl>
      <div className="mt-3 grid gap-1 sm:grid-cols-2">
        <Yes label={t('farmer')} yes={deal?.farmerYes ?? false} />
        <Yes label={t('buyer')} yes={deal?.buyerYes ?? false} />
      </div>
      {/* A stored call shows the receipt link only when the server still has that receipt (receipts are not kept across restarts). */}
      {deal && (deal.state === 'agreed' || deal.farmerYes || deal.buyerYes) && (call.origin !== 'stored' || call.recorded?.receipt === true) && (
        <a
          href={`/api/deals/${encodeURIComponent(call.callId)}/receipt.html`}
          target="_blank"
          rel="noopener noreferrer"
          className="btn-quiet mt-3 flex w-full items-center justify-center gap-1.5 py-1.5 text-sm font-bold text-brand"
        >
          {t('deal_receipt_button')}
        </a>
      )}
      <p className="mt-2 text-sm text-muted">{t('deal_nonbinding')}</p>
    </Card>
  )
}
