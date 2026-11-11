import type { CallView } from '../lib/callState'
import { useI18n } from '../lib/i18n'
import { AlertIcon, CheckIcon } from '../ui/icons'

/** Why the call ended, once it has. */
export default function EndReasonBanner({ call }: { call: CallView }) {
  const { t } = useI18n()
  const reason = call.ended?.reason || call.legs.find((l) => l.ended)?.ended?.reason
  if (!reason) return null

  const isAgreed = reason === 'deal_agreed'
  const isDeclined = reason === 'buyer_declined'
  const isLeft = reason === 'farmer_left'

  const text = isAgreed
    ? t('reason_deal_agreed')
    : isDeclined
      ? t('reason_buyer_declined')
      : isLeft
        ? t('reason_farmer_left')
        : t('reason_call_ended', { reason })

  const bannerClass = isAgreed
    ? 'border-ok bg-ok-soft text-ok'
    : isDeclined
      ? 'border-bad bg-bad-soft text-bad'
      : 'border-warn bg-warn-soft text-warn'

  const Icon = isAgreed ? CheckIcon : AlertIcon

  return (
    <div
      role="status"
      className={`flex items-center gap-3 rounded-xl border p-4 font-bold shadow-card ${bannerClass}`}
    >
      <Icon className="text-xl shrink-0" />
      <p className="text-base">{text}</p>
    </div>
  )
}
