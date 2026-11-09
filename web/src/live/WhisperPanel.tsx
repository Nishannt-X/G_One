import type { CallView } from '../lib/callState'
import { offset } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { AlertIcon, LockIcon } from '../ui/icons'

/** What the assistant told the farmer privately. Styled apart from everything the buyer can hear. */
export default function WhisperPanel({ call }: { call: CallView }) {
  const { t } = useI18n()
  const whispers = call.items.flatMap((item) => (item.kind === 'whisper' ? [item] : [])).reverse()
  return (
    <section aria-label={t('whisper_title')} className="overflow-hidden rounded-2xl border border-whisper-edge bg-whisper-soft shadow-card">
      <div aria-hidden="true" className="hatch h-1.5" />
      <div className="p-4">
        <header className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="flex items-center gap-2 text-lg font-bold text-whisper"><LockIcon /> {t('whisper_title')}</h2>
          <span className="chip bg-whisper text-whisper-soft">{t('farmer_only')}</span>
        </header>
        <p className="mt-1 text-sm font-medium text-muted">{t('whisper_note')}</p>
        {whispers.length === 0 ? (
          <p className="mt-3 text-muted">{t('whisper_empty')}</p>
        ) : (
          <ol className="mt-3 max-h-64 space-y-3 overflow-y-auto">
            {whispers.map((w, i) => (
              <li key={w.key} className={`animate-rise ${i > 0 ? 'border-t border-whisper-edge/40 pt-3 opacity-80' : ''}`}>
                <p className={`flex items-center gap-1.5 text-sm font-bold ${w.whisperKind === 'caution' ? 'text-bad' : 'text-whisper'}`}>
                  {w.whisperKind === 'caution' && <AlertIcon />}
                  {t(`whisper_${w.whisperKind}`)}
                  <span className="font-normal text-muted tabular-nums">{offset(w.atMs, call.startedMs)}</span>
                </p>
                <p lang={w.language} className="mt-0.5 text-lg">{w.text}</p>
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  )
}
