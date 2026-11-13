import { useI18n } from '../lib/i18n'
import { useSampleCall } from '../lib/useSampleCall'
import { PlayIcon } from './icons'
import { ErrorNote } from './parts'

/** Starts the synthetic deal and shows why when it cannot. */
export default function SampleCallButton({ onStarted, className = 'btn-primary' }: { onStarted: () => void; className?: string }) {
  const { t } = useI18n()
  const { play, busy, failure } = useSampleCall(onStarted)
  return (
    <div className="space-y-3">
      <a href="#/demo?autoplay=1" className={className}>
        <PlayIcon /> {t('sample_play')}
      </a>
      {import.meta.env.DEV && <button type="button" className="btn-quiet" disabled={busy} onClick={() => void play()}>
        <PlayIcon /> {busy ? t('sample_starting') : 'Replay in live console (dev)'}
      </button>}
      {import.meta.env.DEV && failure && (
        <div className="text-left">
          <ErrorNote title={t(failure === 'missing' ? 'sample_off' : failure === 'unreachable' ? 'backend_unreachable' : 'sample_failed')}>
            {failure === 'missing' ? <>{t('sample_off_fix')} <code className="font-mono text-sm break-all">G_ONE_CONSOLE_REPLAY_ENABLED=true</code></> : failure === 'unreachable' ? t('backend_unreachable_fix') : undefined}
          </ErrorNote>
        </div>
      )}
    </div>
  )
}
