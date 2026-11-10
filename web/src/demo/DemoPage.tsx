import { PlayIcon } from '../ui/icons'
import CallBar from './CallBar'
import SpeechCredit from './SpeechCredit'
import Stage from './Stage'
import Timeline from './Timeline'
import { creditMode } from './creditMode'
import { SYNTHETIC_CALL_HREF } from './useSyntheticCall'
import ControlStrip from './voiced/ControlStrip'
import HearPanels from './voiced/HearPanels'
import { PhoneRail, PhoneStrip } from './voiced/PhoneMock'
import { useVoicedSample } from './voiced/useVoicedSample'
import VoicedCallBar from './voiced/VoicedCallBar'
import VoicedTimeline from './voiced/VoicedTimeline'

const SAMPLE_LABEL = 'Scripted sample, synthetic voices'

function Intro({ onPlay, busy, label, failed }: { onPlay: () => void; busy: boolean; label: string; failed: boolean }) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-4 py-8 text-center">
      <h2 className="text-3xl leading-tight font-semibold md:text-4xl">Watch a deal cross two languages.</h2>
      <p className="mt-2 max-w-lg text-lg text-muted">A farmer speaks Kannada with some English. A buyer speaks Bengali with some Hindi. Hear every line, what each side receives, and what G-One whispers to the farmer alone. Sound on. Scripted sample, synthetic voices.</p>
      <button type="button" className="btn-accent btn-lg mt-5" disabled={busy} onClick={onPlay}><PlayIcon /> {label}</button>
      <p className="mt-3 text-sm text-faint">
        {failed ? 'The voices did not load. The synthetic call is a text version with no speech.' : <>Or <a className="font-semibold underline" href="#/call">try a call</a> from this browser.</>}
      </p>
    </div>
  )
}

/** The recorded sample only: a scripted call voiced ahead of time, with its deal, phones and receipt. */
export default function DemoPage({ autoplay }: { autoplay: boolean }) {
  const { engine, state: voice, view } = useVoicedSample(autoplay)
  const manifest = voice.manifest
  const on = voice.active && manifest !== null
  const failed = voice.status === 'failed'
  const loading = voice.status === 'loading'
  const openSyntheticCall = () => {
    location.hash = SYNTHETIC_CALL_HREF
  }
  const introLabel = failed ? 'Play a synthetic call' : loading ? `Loading voices ${voice.loaded}/${voice.total || '…'}` : 'Play sample conversation'

  return (
    <Stage
      title="Demo"
      mode={{ text: manifest?.label ?? SAMPLE_LABEL, tone: 'badge-warn' }}
      actions={
        <button type="button" className="btn-quiet" disabled={failed || loading || voice.playing} onClick={() => engine.start()}>
          <PlayIcon /> {voice.playing ? 'Playing…' : on ? 'Play sample again' : 'Play sample'}
        </button>
      }
      dealCall={on ? view : null}
      aside={on && <PhoneStrip manifest={manifest} state={voice} className="min-w-0 lg:hidden" />}
      after={on && <PhoneStrip manifest={manifest} state={voice} className="mx-auto hidden max-w-[1560px] px-4 pb-4 lg:max-[1399px]:block" />}
    >
      {on ? <VoicedCallBar manifest={manifest} state={voice} /> : <CallBar call={null} />}
      <SpeechCredit mode={creditMode(on, null)} />
      {on ? (
        <>
          <VoicedTimeline manifest={manifest} state={voice} onReplayPhones={() => engine.replayPhones()} />
          <div className="min-[1400px]:grid min-[1400px]:grid-cols-[11.75rem_minmax(0,1fr)_11.75rem]">
            <PhoneRail party="farmer" manifest={manifest} state={voice} />
            <div className="min-w-0">
              <ControlStrip engine={engine} state={voice} onTextSample={openSyntheticCall} />
              <HearPanels manifest={manifest} state={voice} />
            </div>
            <PhoneRail party="buyer" manifest={manifest} state={voice} />
          </div>
        </>
      ) : (
        <>
          <Timeline call={null} empty={<Intro onPlay={failed ? openSyntheticCall : () => engine.start()} busy={loading} label={introLabel} failed={failed} />} />
          <ControlStrip engine={engine} state={voice} onTextSample={openSyntheticCall} />
        </>
      )}
      {voice.needsTap && (
        <div className="absolute inset-0 z-30 grid place-items-center bg-page/80 backdrop-blur-sm">
          <button type="button" className="btn-accent btn-lg" onClick={() => engine.start()}><PlayIcon /> Tap to start with sound</button>
        </div>
      )}
    </Stage>
  )
}
