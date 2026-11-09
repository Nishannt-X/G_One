import type { CallView } from '../lib/callState'
import { humanize, shortDate } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { Card } from '../ui/parts'
import { ProvenanceChip } from '../views/ProvenanceChip'

/** What the assistant already knew when this call began. */
export default function MemoryGlance({ call }: { call: CallView }) {
  const { t, lang } = useI18n()
  const memory = call.memory
  return (
    <Card
      title={t('memory_glance')}
      aside={memory ? <span className={`chip ${memory.returning ? 'bg-ok-soft text-ok' : 'bg-sunk text-muted'}`}>{t(memory.returning ? 'memory_returning' : 'memory_first')}</span> : undefined}
    >
      {!memory ? (
        <p className="text-muted">{t('memory_none_loaded')}</p>
      ) : memory.facts.length === 0 && memory.gists.length === 0 ? (
        <p className="text-muted">{t('memory_nothing_yet')}</p>
      ) : (
        <div className="space-y-3">
          <ul className="space-y-1.5">
            {memory.facts.slice(0, 5).map((fact) => (
              <li key={fact.field} className="flex flex-wrap items-center justify-between gap-x-3">
                <span><span className="text-sm text-muted capitalize">{humanize(fact.field)}</span> <span className="font-bold">{fact.value}</span></span>
                <ProvenanceChip origin={fact.provenance.saidOrInferred} />
              </li>
            ))}
          </ul>
          {memory.gists[0] && (
            <div className="rounded-lg bg-sunk p-3">
              <p className="text-xs font-semibold text-muted">{t('memory_last_call', { date: shortDate(memory.gists[0].startedMs, lang) })}</p>
              <p>{memory.gists[0].summary}</p>
            </div>
          )}
          {memory.callerKey && <a className="inline-block font-semibold text-brand underline underline-offset-4" href={`#/memory/${memory.callerKey}`}>{t('memory_open')}</a>}
        </div>
      )}
    </Card>
  )
}
