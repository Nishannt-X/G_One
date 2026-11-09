import type { CallView } from '../lib/callState'
import { rupees } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { comparePrice, latestReference, meterPosition, METER_RANGE_PCT as RANGE_PCT } from '../lib/pricing'
import { Card } from '../ui/parts'

/** How the offer on the table compares with the newest dated reference. Never says "fair": the reference is dated. */
export default function PriceMeter({ call }: { call: CallView }) {
  const { t } = useI18n()
  const deal = call.deal
  const reference = latestReference(call.tools, deal?.commodity ?? null)
  const comparison = comparePrice(deal?.price ?? null, deal?.unit ?? null, reference)
  const delta = comparison?.deltaPct ?? 0
  const same = Math.abs(delta) < 0.5
  return (
    <Card title={t('meter_title')}>
      <div className="relative h-11 pt-5" role="img" aria-label={comparison ? t(delta >= 0 ? 'meter_above' : 'meter_below', { pct: Math.abs(delta).toFixed(1) }) : t('meter_waiting')}>
        <div className="flex h-2.5 gap-0.5 overflow-hidden rounded-full">
          <div className="flex-1 bg-bad opacity-60" />
          <div className="flex-1 bg-sunk" />
          <div className="flex-1 bg-ok opacity-60" />
        </div>
        <div aria-hidden="true" className="absolute top-3.5 left-1/2 h-5 w-0.5 -translate-x-1/2 bg-ink" />
        {comparison && (
          // Moved by transform on a full-width layer, so it glides without touching layout.
          <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-0 transition-transform duration-500" style={{ transform: `translateX(calc(${meterPosition(delta)}% - 50%))` }}>
            <div className="mx-auto w-fit -translate-y-0.5 text-center">
              <span className="block rounded bg-ink px-1.5 text-xs leading-4 font-bold text-page tabular-nums">{delta > 0 ? '+' : ''}{delta.toFixed(1)}%</span>
              <span className="mx-auto mt-px block h-0 w-0 border-x-4 border-t-[6px] border-x-transparent border-t-ink" />
            </div>
          </div>
        )}
      </div>
      <div className="flex justify-between text-xs text-muted tabular-nums">
        <span>−{RANGE_PCT}%</span>
        <span>{t('meter_reference')}</span>
        <span>+{RANGE_PCT}%</span>
      </div>
      <div className="mt-2 min-h-[4.5rem]">
        {comparison && reference ? (
          <>
            <p className="font-semibold">
              {same ? t('meter_same') : t(delta > 0 ? 'meter_above' : 'meter_below', { pct: Math.abs(delta).toFixed(1) })}
            </p>
            <p className="text-sm text-muted">
              {t('meter_offer', { offer: rupees(comparison.offerPerQuintal), reference: rupees(comparison.referencePerQuintal) })}
            </p>
            <p className="text-sm text-muted">{t('meter_dated', { source: reference.source ?? t('meter_snapshot'), date: reference.date ?? t('meter_no_date') })}</p>
          </>
        ) : (
          <p className="text-muted">{t(reference ? 'meter_waiting_offer' : 'meter_waiting')}</p>
        )}
      </div>
    </Card>
  )
}
