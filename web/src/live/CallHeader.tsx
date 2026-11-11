import type { CallView } from '../lib/callState'
import { duration, languageLabel, shortId } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { useNow } from '../lib/useNow'

function Timer({ call }: { call: CallView }) {
  const now = useNow(!call.ended)
  const ms = call.ended ? call.ended.durationMs : now - call.startedMs
  return <span className="font-mono text-3xl font-bold tabular-nums md:text-4xl">{duration(ms)}</span>
}

function startedAt(ms: number): string {
  return new Date(ms).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export default function CallHeader({ call, calls, onPick }: { call: CallView; calls: CallView[]; onPick: (callId: string) => void }) {
  const { t, lang } = useI18n()
  const live = !call.ended
  const stored = call.origin === 'stored'
  const languages = [...new Set(call.legs.map((l) => l.language).filter(Boolean))]
  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
      <span className={`chip min-h-8 px-3 ${live ? 'bg-ok-soft text-ok' : stored ? 'bg-brand-soft text-brand' : 'bg-sunk text-muted'}`}>
        <span className="relative flex h-2.5 w-2.5" aria-hidden="true">
          {live && <span className="absolute inline-flex h-full w-full animate-beat rounded-full bg-current" />}
          <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-current" />
        </span>
        {live ? t('on_the_line') : stored ? t('call_stored') : t('call_ended')}
      </span>
      <Timer call={call} />
      {stored && <p className="text-muted">{t('call_started_at', { time: startedAt(call.startedMs) })}</p>}
      <p className="text-muted">
        {(languages.length ? languages : [call.language]).map((l) => languageLabel(l, lang)).join(' ⇄ ')}
      </p>
      <p className="font-mono text-sm text-faint" title={call.callId}>{shortId(call.callId, 22)}</p>
      {calls.length > 1 && (
        <label className="ml-auto flex items-center gap-2 text-sm font-semibold">
          {t('calls')}
          <select className="field min-h-11 w-auto font-mono text-sm" value={call.callId} onChange={(e) => onPick(e.target.value)}>
            {[...calls].reverse().map((c) => (
              <option key={c.callId} value={c.callId}>
                {shortId(c.callId, 18)}{c.ended ? ` (${t('ended')})` : ''}
              </option>
            ))}
          </select>
        </label>
      )}
    </div>
  )
}
