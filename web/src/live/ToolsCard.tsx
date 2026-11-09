import type { CallView } from '../lib/callState'
import { millis, offset } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { CheckIcon, ChevronIcon, CrossIcon } from '../ui/icons'
import { Card } from '../ui/parts'

export default function ToolsCard({ call }: { call: CallView }) {
  const { t } = useI18n()
  return (
    <Card title={t('tools_title')}>
      {call.tools.length === 0 ? (
        <p className="text-muted">{t('tools_empty')}</p>
      ) : (
        <ul className="max-h-72 space-y-1.5 overflow-y-auto">
          {[...call.tools].reverse().map((tool) => (
            <li key={tool.key} className="animate-rise">
              <details className="group rounded-lg bg-sunk">
                <summary className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg px-3 py-1.5">
                  <span className={tool.ok ? 'text-ok' : 'text-bad'}>{tool.ok ? <CheckIcon /> : <CrossIcon />}</span>
                  <span className="min-w-0 flex-1 truncate font-mono text-sm font-semibold">{tool.tool}</span>
                  <span className="text-sm text-muted tabular-nums">{millis(tool.durationMs)} · {offset(tool.atMs, call.startedMs)}</span>
                  <ChevronIcon className="text-muted transition-transform group-open:rotate-180" />
                </summary>
                <div className="space-y-1 px-3 pb-3 text-sm">
                  <p className="font-semibold">{t('tools_result')}</p>
                  <p className="text-muted">{tool.result}</p>
                  {tool.args && <pre className="overflow-x-auto rounded bg-surface p-2 font-mono text-xs">{JSON.stringify(tool.args, null, 2)}</pre>}
                </div>
              </details>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
