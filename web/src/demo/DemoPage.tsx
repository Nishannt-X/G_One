import { useCallback, useEffect, useRef, useState, type Dispatch } from 'react'
import { api } from '../lib/api'
import { callPhase, legActivity, legByRole, pickCall, type CallPhase, type CallView, type FeedAction, type FeedState } from '../lib/callState'
import { duration, languageLabel } from '../lib/format'
import { useNow } from '../lib/useNow'
import type { FeedStatus } from '../lib/useConsoleFeed'
import { useDemoLines, useSimulatorConfig } from '../lib/useSimulatorSetup'
import { useCallSimulator } from '../simulator/useCallSimulator'
import type { LegRole } from '../types/console'
import { PlayIcon } from '../ui/icons'
import DealPanel from './DealPanel'
import LegDock from './LegDock'
import PhoneJoinCard from './PhoneJoinCard'
import SpeechCredit from './SpeechCredit'
import { creditMode } from './speechCredit'
import { usePhoneJoin } from './phoneJoin'
import { Avatar, Waveform } from './parts'
import Timeline from './Timeline'
import ControlStrip from './voiced/ControlStrip'
import HearPanels from './voiced/HearPanels'
import { PhoneRail, PhoneStrip } from './voiced/PhoneMock'
import { useVoicedSample } from './voiced/useVoicedSample'
import VoicedCallBar from './voiced/VoicedCallBar'
import VoicedTimeline from './voiced/VoicedTimeline'

const PHASE_TEXT = (phase: CallPhase): string => {
  switch (phase.kind) {
    case 'ended': return 'The call has ended'
    case 'listening': return 'Listening'
    case 'translating': return `Translating for the ${phase.from === 'farmer' ? 'buyer' : 'farmer'}`
    case 'whisper': return 'Whispering to the farmer'
    case 'speaking': return `Speaking to the ${phase.to}`
    case 'tool': return phase.tool.includes('mandi') ? 'Checking the mandi reference' : phase.tool.includes('buyer') ? 'Looking for a buyer' : 'Looking something up'
  }
}

const FEED_LABEL: Record<FeedStatus, { text: string; dot: string }> = {
  connected: { text: 'Engine connected', dot: 'bg-ok' },
  connecting: { text: 'Connecting…', dot: 'bg-warn' },
  offline: { text: 'Engine offline', dot: 'bg-bad' },
}

function Side({ role, call, now }: { role: LegRole; call: CallView | null; now: number }) {
  const leg = call ? legByRole(call, role) : undefined
  const activity = call ? legActivity(call, role, now) : 'idle'
  const name = role === 'farmer' ? call?.memory?.facts.find((f) => f.field === 'name')?.value ?? 'Farmer' : 'Buyer'
  const speaking = activity === 'person' || activity === 'assistant' || activity === 'whisper'
  const state = !leg ? (role === 'buyer' && call ? 'Not on the line yet' : 'Waiting') : leg.ended ? 'Left the call' : activity === 'whisper' ? 'Hearing a private note' : activity === 'person' ? 'Speaking' : activity === 'assistant' ? 'Hearing G-One' : 'On the line'
  const right = role === 'buyer'
  return (
    <div className={`flex min-w-0 flex-1 items-center gap-2.5 ${right ? 'flex-row-reverse text-right' : ''}`}>
      <span className="hidden sm:block"><Avatar who={role} speaking={speaking} /></span>
      <div className="min-w-0">
        <p className="truncate text-lg leading-tight font-semibold">{name}</p>
        <p className="truncate text-sm leading-tight text-muted">{leg && <span className="hidden sm:inline">{languageLabel(leg.language)} · </span>}{state}</p>
      </div>
      <Waveform active={speaking} className={`hidden xl:flex ${role === 'farmer' ? 'text-farmer' : 'text-buyer'}`} />
    </div>
  )
}

/** Refreshes on its own clock, so the conversation below does not re-render with it. */
function CallBar({ call }: { call: CallView | null }) {
  const running = Boolean(call && !call.ended)
  const now = useNow(running, 300)
  const phase = call ? callPhase(call, now) : null
  const busy = phase !== null && phase.kind !== 'listening' && phase.kind !== 'ended'
  return (
    <div className="flex items-center gap-2 border-b border-line px-3 py-2.5 sm:gap-4 sm:px-5">
      <Side role="farmer" call={call} now={now} />
      <div className="flex w-28 shrink-0 flex-col items-center sm:w-52">
        <span className="flex items-center gap-1.5"><Avatar who="assistant" size="sm" speaking={busy} /><span className="font-semibold">G-One</span></span>
        <span aria-live="polite" className="w-full truncate text-center text-xs leading-tight text-muted sm:text-sm">{phase ? PHASE_TEXT(phase) : 'Ready'}</span>
      </div>
      <Side role="buyer" call={call} now={now} />
    </div>
  )
}

function Clock({ call }: { call: CallView }) {
  const now = useNow(!call.ended, 1000)
  return <span className="font-semibold tabular-nums">{duration(call.ended ? call.ended.durationMs : now - call.startedMs)}</span>
}

function Intro({ onPlay, busy, label }: { onPlay: () => void; busy: boolean; label: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-4 py-8 text-center">
      <h2 className="text-3xl leading-tight font-semibold md:text-4xl">Watch a deal cross two languages.</h2>
      <p className="mt-2 max-w-lg text-lg text-muted">A farmer speaks Kannada with some English. A buyer speaks Bengali with some Hindi. Hear every line, what each side receives, and what G-One whispers to the farmer alone. Sound on. Scripted sample, synthetic voices.</p>
      <button type="button" className="btn-accent btn-lg mt-5" disabled={busy} onClick={onPlay}><PlayIcon /> {label}</button>
      <p className="mt-3 text-sm text-faint">Or start your own call from the panels below.</p>
    </div>
  )
}

export default function DemoPage({ feed, status, dispatch, autoplay, lan }: { feed: FeedState; status: FeedStatus; dispatch: Dispatch<FeedAction>; autoplay: boolean; lan: string | null }) {
  const farmer = useCallSimulator()
  const buyer = useCallSimulator()
  const { config, failed } = useSimulatorConfig()
  const lines = useDemoLines()
  const [starting, setStarting] = useState(false)
  const stopMock = useRef<(() => void) | null>(null)
  const autoplayed = useRef(false)
  const voiced = useVoicedSample(autoplay)
  const { engine, state: voice, view: voicedView } = voiced

  const onCall = farmer.state.status === 'live' || farmer.state.status === 'connecting' || farmer.state.status === 'reconnecting'
  // Follow this tab's call only while it runs, so a sample played afterwards takes over the view.
  const ownCallId = onCall ? farmer.state.callId : null
  const call = pickCall(feed, ownCallId)
  const sampleRunning = Boolean(call && call.origin !== 'feed' && !call.ended)

  const playSample = useCallback(async () => {
    setStarting(true)
    stopMock.current?.()
    try {
      await api('/api/dev/console/replay?speed=1', { method: 'POST' })
    } catch {
      // The replay switch is off or the backend is away: play the same sale from the page.
      const { playMock } = await import('../dev/mock')
      stopMock.current = playMock((event) => dispatch({ type: 'event', event, mock: true }), 1)
    } finally {
      setStarting(false)
    }
  }, [dispatch])

  useEffect(() => {
    if (!autoplay || autoplayed.current || voice.status !== 'failed') return
    autoplayed.current = true
    void playSample()
  }, [autoplay, playSample, voice.status])

  useEffect(() => () => stopMock.current?.(), [])

  const voicedOn = voice.active && voice.manifest !== null && !onCall
  const voiceManifest = voice.manifest
  const startSample = () => {
    if (voice.status === 'failed') void playSample()
    else engine.start()
  }
  const introLabel = voice.status === 'loading' ? `Loading voices ${voice.loaded}/${voice.total || '…'}` : 'Play sample conversation'

  const buyerCallId = ownCallId ?? (call && !call.ended && call.origin === 'feed' ? call.callId : '')
  const buyerReady = Boolean(buyerCallId)
  const phone = usePhoneJoin(buyerCallId, lan)
  const [buyerInThisTab, setBuyerInThisTab] = useState(false)
  const buyerLeg = call ? legByRole(call, 'buyer') : undefined
  const buyerDocked = buyer.state.status === 'live' || buyer.state.status === 'connecting' || buyer.state.status === 'reconnecting'
  const showPhoneCard = phone.kind === 'ready' && !buyerInThisTab && !buyerDocked && (!buyerLeg || Boolean(buyerLeg.ended))
  const feedLabel = FEED_LABEL[status]
  const originLabel = voicedOn && voiceManifest ? voiceManifest.label : !call ? 'No call yet' : call.origin === 'feed' ? (call.ended ? 'Call finished' : 'Live call') : 'Sample conversation, synthetic'

  return (
    <>
    <div className="mx-auto flex max-w-[1560px] flex-col gap-3 px-3 py-3 sm:px-4 lg:h-[calc(100dvh-4.0625rem)]">
      <div className="flex min-h-11 flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 items-center gap-3">
          <h1 className="text-2xl font-semibold">Demo</h1>
          <span className={`badge ${voicedOn || (call && call.origin !== 'feed') ? 'badge-warn' : call && !call.ended ? 'badge-ok' : ''}`}>{originLabel}</span>
          {call && !voicedOn && <Clock call={call} />}
        </div>
        <div className="flex items-center gap-3">
          <span role="status" className="flex items-center gap-1.5 text-sm font-medium text-muted"><span aria-hidden="true" className={`h-2 w-2 rounded-full ${feedLabel.dot}`} /> {feedLabel.text}</span>
          <button type="button" className="btn-quiet" disabled={starting || sampleRunning || onCall || voice.status === 'loading' || voice.playing} onClick={startSample}>
            <PlayIcon /> {sampleRunning || voice.playing ? 'Playing…' : voicedOn || call ? 'Play sample again' : 'Play sample'}
          </button>
        </div>
      </div>

      <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[minmax(0,1fr)_23.5rem]">
        <section aria-label="Conversation" className="card relative flex h-[calc(100dvh-5rem)] min-h-[34rem] min-w-0 flex-col overflow-hidden lg:h-auto lg:min-h-0">
          {voicedOn && voiceManifest ? <VoicedCallBar manifest={voiceManifest} state={voice} /> : <CallBar call={call} />}
          <SpeechCredit mode={creditMode(voicedOn && voiceManifest !== null, call?.origin ?? null, lines.length > 0)} />
          {voicedOn && voiceManifest ? <VoicedTimeline manifest={voiceManifest} state={voice} onReplayPhones={() => engine.replayPhones()} /> : <Timeline call={call} empty={<Intro onPlay={startSample} busy={starting || voice.status === 'loading'} label={introLabel} />} />}
          {voicedOn && voiceManifest ? (
            <div className="min-[1400px]:grid min-[1400px]:grid-cols-[11.75rem_minmax(0,1fr)_11.75rem]">
              <PhoneRail party="farmer" manifest={voiceManifest} state={voice} />
              <div className="min-w-0">
                <ControlStrip engine={engine} state={voice} onTextSample={() => void playSample()} />
                <HearPanels manifest={voiceManifest} state={voice} />
              </div>
              <PhoneRail party="buyer" manifest={voiceManifest} state={voice} />
            </div>
          ) : <><ControlStrip engine={engine} state={voice} onTextSample={() => void playSample()} /><div className="grid border-t border-line bg-page/60 md:grid-cols-2 md:divide-x md:divide-line">
            <div className="p-3 md:h-[10.25rem]">
              <LegDock role="farmer" sim={farmer} config={config} lines={lines} callId="" canStart={!sampleRunning && status !== 'offline'} lockedReason={sampleRunning ? 'Wait for the sample to finish.' : 'The engine is offline, so calls cannot start.'} />
            </div>
            <div className="border-t border-line p-3 md:h-[10.25rem] md:border-t-0">
              {showPhoneCard ? (
                <PhoneJoinCard hosts={phone.hosts} port={phone.port} callId={buyerCallId} onUseThisTab={() => setBuyerInThisTab(true)} />
              ) : (
                <LegDock role="buyer" sim={buyer} config={config} lines={lines} callId={buyerCallId} canStart={buyerReady} lockedReason="Opens once a farmer is on a call." hint={phone.kind === 'ready' ? 'Or play the buyer from this tab.' : phone.hint} />
              )}
            </div>
          </div></>}
          {voice.needsTap && (
            <div className="absolute inset-0 z-30 grid place-items-center bg-page/80 backdrop-blur-sm">
              <button type="button" className="btn-accent btn-lg" onClick={() => engine.start()}><PlayIcon /> Tap to start with sound</button>
            </div>
          )}
        </section>
        {voicedOn && voiceManifest && <PhoneStrip manifest={voiceManifest} state={voice} className="min-w-0 lg:hidden" />}
        <div className="min-h-0 min-w-0">
          <DealPanel call={voicedOn ? voicedView : call} />
        </div>
      </div>
      {failed && <p role="alert" className="text-sm font-medium text-bad">The backend did not answer, so calls cannot start. The sample still plays.</p>}
    </div>
    {voicedOn && voiceManifest && <PhoneStrip manifest={voiceManifest} state={voice} className="mx-auto hidden max-w-[1560px] px-4 pb-4 lg:max-[1399px]:block" />}
    </>
  )
}
