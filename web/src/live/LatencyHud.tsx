import { useI18n } from '../lib/i18n'
import type { CallView, TurnTiming } from '../lib/callState'
import { millis } from '../lib/format'
import { stagesOf, summarize } from '../lib/latency'
import { Card } from '../ui/parts'

const STAGE_BAR = { transcript: 'bg-farmer', translation: 'bg-assistant', reply: 'bg-buyer' } as const

function Bars({ timings }: { timings: TurnTiming[] }) {
  const { t } = useI18n()
  const recent = timings.slice(-16)
  const stages = recent.map(stagesOf)
  const peak = Math.max(1, ...stages.map((s) => s.transcript + Math.max(s.translation ?? 0, s.reply ?? 0)))
  return (
    <div className="flex h-14 items-end gap-1" role="img" aria-label={t('latency_chart')}>
      {stages.map((s, i) => {
        const after = s.translation ?? s.reply
        const kind = s.translation !== null ? 'translation' : 'reply'
        return (
          <div
            key={recent[i].key}
            title={`${t(recent[i].role)}: ${t('latency_transcript')} ${millis(s.transcript)}${after !== null ? `, ${t(kind === 'translation' ? 'latency_translation' : 'latency_reply')} ${millis(after)}` : ''}`}
            className="flex h-full max-w-6 flex-1 flex-col justify-end gap-0.5"
          >
            {after !== null && <div className={`min-h-0.5 rounded-t ${STAGE_BAR[kind]}`} style={{ height: `${(after / peak) * 100}%` }} />}
            <div className={`min-h-0.5 ${after === null ? 'rounded-t' : ''} ${STAGE_BAR.transcript}`} style={{ height: `${(s.transcript / peak) * 100}%` }} />
          </div>
        )
      })}
    </div>
  )
}

/** Per-turn timings, measured on the server's clock from the events' own timestamps. */
export default function LatencyHud({ call }: { call: CallView }) {
  const { t } = useI18n()
  if (call.timings.length === 0) return null
  const summary = summarize(call.timings)
  const scripted = call.origin === 'mock' || call.origin === 'sample'
  const cells = [
    { label: t('latency_transcript'), hint: t('latency_transcript_hint'), value: summary.last?.transcript ?? null, median: summary.median.transcript, bar: STAGE_BAR.transcript },
    { label: t('latency_translation'), hint: t('latency_translation_hint'), value: summary.last?.translation ?? null, median: summary.median.translation, bar: STAGE_BAR.translation },
    { label: t('latency_reply'), hint: t('latency_reply_hint'), value: summary.last?.reply ?? null, median: summary.median.reply, bar: STAGE_BAR.reply },
  ]
  return (
    <Card
      title={t('latency_title')}
      aside={<span className={`chip ${scripted ? 'bg-warn-soft text-warn' : 'bg-ok-soft text-ok'}`}>{t(scripted ? 'latency_scripted' : 'latency_measured')}</span>}
    >
      <dl className="grid grid-cols-3 gap-2">
        {cells.map((cell) => (
          <div key={cell.label} title={cell.hint} className="min-w-0">
            <dt className="flex items-start gap-1.5 text-xs leading-tight font-semibold text-muted">
              <i aria-hidden="true" className={`mt-0.5 h-2.5 w-2.5 shrink-0 rounded-sm ${cell.bar}`} /> {cell.label}
            </dt>
            <dd className="mt-1 font-mono text-xl font-bold tabular-nums">{cell.value !== null ? millis(cell.value) : '—'}</dd>
            <dd className="text-xs text-muted tabular-nums">{t('latency_median')} {cell.median !== null ? millis(cell.median) : '—'}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-3">
        {call.timings.length > 0 ? <Bars timings={call.timings} /> : <p className="flex h-14 items-center text-muted">{t('latency_empty')}</p>}
      </div>
      <p className="mt-2 text-xs text-muted">{t(scripted ? 'latency_scripted_note' : 'latency_note')}</p>
    </Card>
  )
}
