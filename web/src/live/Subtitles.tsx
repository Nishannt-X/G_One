import { legByRole, translationKey, type CallView, type TimelineItem } from '../lib/callState'
import { languageLabel, offset } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { useStickToBottom } from '../lib/useStickToBottom'
import type { LegRole } from '../types/console'
import { ChevronIcon, LockIcon, Logo } from '../ui/icons'
import { Empty } from '../ui/parts'

const SIDE: Record<LegRole, string> = { farmer: 'justify-start', buyer: 'justify-end' }
const SPEAKER: Record<LegRole, { text: string; rule: string; soft: string }> = {
  farmer: { text: 'text-farmer', rule: 'border-farmer', soft: 'bg-farmer-soft' },
  buyer: { text: 'text-buyer', rule: 'border-buyer', soft: 'bg-buyer-soft' },
}

function Line({ item, call, isLast }: { item: TimelineItem; call: CallView; isLast: boolean }) {
  const { t, lang } = useI18n()
  const when = offset(item.atMs, call.startedMs)
  if (item.kind === 'whisper') {
    return (
      <li className="flex animate-rise justify-start">
        <p className="inline-flex items-center gap-2 rounded-full border border-dashed border-whisper-edge bg-whisper-soft px-3 py-1 text-sm font-semibold text-whisper">
          <LockIcon /> {t('whisper_marker', { kind: t(`whisper_${item.whisperKind}`) })}
          <span className="font-normal tabular-nums">{when}</span>
        </p>
      </li>
    )
  }
  if (item.kind === 'assistant') {
    return (
      <li className={`flex animate-rise ${SIDE[item.role]}`}>
        <div className="max-w-[min(38rem,92%)] rounded-2xl bg-assistant-soft px-4 py-2.5">
          <p className="flex flex-wrap items-center gap-x-2 text-sm font-semibold text-assistant">
            <Logo width="1.3em" height="1.3em" /> {t('assistant_to', { role: t(item.role) })}
            <span className="font-normal text-muted tabular-nums">{languageLabel(item.language, lang)} · {when}</span>
          </p>
          <p lang={item.language} className="mt-0.5 text-lg">{item.text}</p>
        </div>
      </li>
    )
  }
  const style = SPEAKER[item.role]
  const translation = call.translations[translationKey(item.legId, item.startedMs)]
  const other: LegRole = item.role === 'farmer' ? 'buyer' : 'farmer'
  const pending = !translation && isLast && !call.ended && legByRole(call, other) !== undefined
  return (
    <li className={`flex animate-rise ${SIDE[item.role]}`}>
      <div className={`max-w-[min(38rem,92%)] rounded-2xl border-2 ${style.rule} bg-surface px-4 py-3`}>
        <p className="flex flex-wrap items-center gap-x-2 text-sm font-bold">
          <span className={style.text}>{t(item.role)}</span>
          <span className="font-normal text-muted tabular-nums">{languageLabel(item.language, lang)} · {when}</span>
        </p>
        <p lang={item.language} className="mt-0.5 text-xl font-medium md:text-[1.4rem]">{item.text}</p>
        {translation && (
          <div className="mt-2 border-t border-line pt-2">
            <p className="text-xs font-semibold text-muted">{t('translated_for', { role: t(translation.toRole), language: languageLabel(translation.toLanguage, lang) })}</p>
            <p lang={translation.toLanguage} className="text-lg text-muted">{translation.text}</p>
          </div>
        )}
        {pending && <p className="mt-2 animate-shimmer border-t border-line pt-2 text-sm text-muted">{t('translating')}</p>}
      </div>
    </li>
  )
}

/** The call as subtitles: the farmer's side on the left, the buyer's on the right, each line with its translation. */
export default function Subtitles({ call, className = 'h-[min(62vh,40rem)]' }: { call: CallView; className?: string }) {
  const { t } = useI18n()
  const { ref, onScroll, away, jump } = useStickToBottom(call.items.length + Object.keys(call.translations).length)
  return (
    <section aria-label={t('subtitles')} className={`card relative flex flex-col overflow-hidden ${className}`}>
      <header className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
        <h2 className="text-lg font-bold">{t('subtitles')}</h2>
        <p className="text-sm text-muted">{t('subtitles_hint')}</p>
      </header>
      <div ref={ref} onScroll={onScroll} tabIndex={0} role="log" aria-live="polite" aria-relevant="additions" className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-3 md:p-4">
        {call.items.length === 0 ? (
          <Empty title={t('subtitles_empty')}>{t('subtitles_empty_body')}</Empty>
        ) : (
          <ol className="space-y-3">
            {call.items.map((item, i) => (
              <Line key={item.key} item={item} call={call} isLast={i === call.items.length - 1} />
            ))}
          </ol>
        )}
      </div>
      {away && (
        <button type="button" onClick={jump} className="btn-primary absolute right-4 bottom-4 animate-rise shadow-card">
          <ChevronIcon /> {t('jump_latest')}
        </button>
      )}
    </section>
  )
}
