import { PlayIcon } from '../ui/icons'
import DealPanel from './DealPanel'
import SpeechCredit from './SpeechCredit'
import { creditMode } from './creditMode'
import CinemaStage, { StageIntro } from './voiced/CinemaStage'
import ControlStrip from './voiced/ControlStrip'
import { useVoicedSample } from './voiced/useVoicedSample'
import VoicedTimeline from './voiced/VoicedTimeline'

const SAMPLE_LABEL = 'Scripted sample, synthetic voices'

/** The recorded sample, staged: a scripted call voiced ahead of time, with its deal, phones and receipt. */
export default function DemoPage({ autoplay }: { autoplay: boolean }) {
  const { engine, state: voice, view } = useVoicedSample(autoplay)
  const manifest = voice.manifest
  const on = voice.active && manifest !== null
  const ready = voice.status === 'ready'

  return (
    <div className="mx-auto max-w-[1440px] px-3 py-5 sm:px-6 lg:py-6">
      <header className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold sm:text-3xl">Demo</h1>
          <span data-testid="page-mode" className="badge badge-warn">{manifest?.label ?? SAMPLE_LABEL}</span>
        </div>
        <div className="flex min-w-0 items-center gap-3">
          <div className="hidden min-w-0 sm:block"><SpeechCredit mode={creditMode(on, null)} /></div>
          {on && (
            <button type="button" className="btn-quiet hidden sm:inline-flex" disabled={!ready || voice.playing} onClick={() => engine.start()}>
              <PlayIcon /> {voice.playing ? 'Playing…' : 'Play again'}
            </button>
          )}
        </div>
      </header>

      <CinemaStage manifest={manifest} state={voice} engine={engine} view={view} intro={<StageIntro state={voice} onPlay={() => engine.start()} onRetry={() => void engine.load()} />} />

      {on && (
        <>
          <div className="card mt-3 overflow-hidden [&>div]:border-t-0">
            <ControlStrip engine={engine} state={voice} />
          </div>
          <div className="sm:hidden"><SpeechCredit mode={creditMode(on, null)} /></div>
          <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1fr)_23.5rem]">
            <section aria-label="Transcript" className="card flex h-[30rem] min-w-0 flex-col overflow-hidden lg:h-[38rem]">
              <h2 className="border-b border-line px-4 py-3 text-sm font-bold tracking-wide text-muted sm:px-5">Full transcript</h2>
              <VoicedTimeline manifest={manifest} state={voice} onReplayPhones={() => engine.replayPhones()} />
            </section>
            <div className="min-w-0 lg:h-[38rem]">
              <DealPanel call={view} />
            </div>
          </div>
        </>
      )}

      {voice.needsTap && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-night/70 backdrop-blur-sm">
          <button type="button" className="btn-accent btn-lg" onClick={() => engine.start()}><PlayIcon /> Tap to start with sound</button>
        </div>
      )}
    </div>
  )
}
