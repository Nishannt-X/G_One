import { savedGistText, useSavedGist } from '../demo/savedGist'
import type { CallView } from '../lib/callState'
import { openFollowUps } from '../lib/farmers'
import { humanize, shortDate } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { CheckIcon } from '../ui/icons'
import { Card } from '../ui/parts'
import { ProvenanceChip } from '../views/ProvenanceChip'

const rank = (field: string) => (field === 'name' ? 0 : field.startsWith('crop:') ? 1 : 2)

/**
 * What the assistant already knew when this call began (name and crops first, so five rows still show the crops, then
 * the open follow-ups and the last call), and after hang-up whether this call's summary was confirmed saved.
 */
export default function MemoryGlance({ call }: { call: CallView }) {
  const { t, lang } = useI18n()
  const memory = call.memory
  const saved = useSavedGist(call)
  const open = memory ? openFollowUps(memory.gists) : []
  return (
    <Card
      title={t('memory_glance')}
      aside={memory ? <span className={`chip ${memory.returning ? 'bg-ok-soft text-ok' : 'bg-sunk text-muted'}`}>{t(memory.returning ? 'memory_returning' : 'memory_first')}</span> : undefined}
    >
      <div className="space-y-3">
        {!memory ? (
          <p className="text-muted">{t('memory_none_loaded')}</p>
        ) : memory.facts.length === 0 && memory.gists.length === 0 ? (
          <p className="text-muted">{t('memory_nothing_yet')}</p>
        ) : (
          <>
            <ul className="space-y-1.5">
              {[...memory.facts].sort((a, b) => rank(a.field) - rank(b.field)).slice(0, 5).map((fact) => (
                <li key={fact.field} className="flex flex-wrap items-center justify-between gap-x-3">
                  <span><span className="text-sm text-muted capitalize">{fact.field.startsWith('crop:') ? 'crop' : humanize(fact.field)}</span> <span className="font-bold">{fact.value}</span></span>
                  <ProvenanceChip origin={fact.provenance.saidOrInferred} />
                </li>
              ))}
            </ul>
            <div>
              <p className="text-xs font-semibold text-muted">{t('memory_open_items')}</p>
              {open.length > 0 ? (
                <ul className="list-disc pl-5">{open.map((item) => <li key={item}>{item}</li>)}</ul>
              ) : <p className="text-muted">{t('memory_open_none')}</p>}
            </div>
            {memory.gists[0] && (
              <div className="rounded-lg bg-sunk p-3">
                <p className="text-xs font-semibold text-muted">{t('memory_last_call', { date: shortDate(memory.gists[0].startedMs, lang) })}</p>
                <p>{memory.gists[0].summary}</p>
              </div>
            )}
          </>
        )}
        {saved && (
          <p role="status" className={`flex items-start gap-1.5 rounded-lg border px-3 py-2 font-medium ${saved.state === 'saved' ? 'border-ok/40 bg-ok-soft text-ok' : 'border-line text-muted'} ${saved.state === 'saving' ? 'animate-shimmer' : ''}`}>
            {saved.state === 'saved' && <span className="mt-0.5"><CheckIcon /></span>}<span>{savedGistText(saved)}</span>
          </p>
        )}
        {memory?.callerKey && <a className="inline-block font-semibold text-brand underline underline-offset-4" href={`#/memory/${memory.callerKey}`}>{t('memory_open')}</a>}
      </div>
    </Card>
  )
}
