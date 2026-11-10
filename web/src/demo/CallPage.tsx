import { useEffect, useRef, useState, type Dispatch } from 'react'
import { legByRole, pickCall, type FeedAction, type FeedState } from '../lib/callState'
import type { FeedStatus } from '../lib/useConsoleFeed'
import { useDemoLines, useSimulatorConfig } from '../lib/useSimulatorSetup'
import { useCallSimulator } from '../simulator/useCallSimulator'
import { PlayIcon } from '../ui/icons'
import CallBar, { CallClock, FeedStatusLabel } from './CallBar'
import LegDock from './LegDock'
import PhoneJoinCard from './PhoneJoinCard'
import SpeechCredit from './SpeechCredit'
import Stage from './Stage'
import Timeline from './Timeline'
import { creditMode } from './creditMode'
import { usePhoneJoin } from './phoneJoin'
import { useSyntheticCall } from './useSyntheticCall'

function Intro({ onSynthetic, busy }: { onSynthetic: () => void; busy: boolean }) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-4 py-8 text-center">
      <h2 className="text-3xl leading-tight font-semibold md:text-4xl">Talk to G-One from this browser.</h2>
      <p className="mt-2 max-w-lg text-lg text-muted">Tap Call as farmer below and speak into your microphone. Once you are on the call, a buyer can join from a phone by scanning the code, or from this tab. No phone number is dialled.</p>
      <p className="mt-3 text-sm text-faint">
        No microphone? <button type="button" className="font-semibold underline" disabled={busy} onClick={onSynthetic}>Play a synthetic call</button>, a scripted text call with no speech.
      </p>
    </div>
  )
}

interface Props {
  feed: FeedState
  status: FeedStatus
  dispatch: Dispatch<FeedAction>
  lan: string | null
  /** Set by #/call?synthetic=1: play the synthetic call once on arrival. */
  synthetic: boolean
}

/** The browser call: the farmer on this tab's microphone, the buyer on a phone or a second panel, and G-One between them. */
export default function CallPage({ feed, status, dispatch, lan, synthetic }: Props) {
  const farmer = useCallSimulator()
  const buyer = useCallSimulator()
  const { config, failed } = useSimulatorConfig()
  const lines = useDemoLines()
  const { play, starting } = useSyntheticCall(dispatch)
  const autoplayed = useRef(false)

  const onCall = farmer.state.status === 'live' || farmer.state.status === 'connecting' || farmer.state.status === 'reconnecting'
  // Follow this tab's call only while it runs, so a synthetic call played afterwards takes over the view.
  const ownCallId = onCall ? farmer.state.callId : null
  const call = pickCall(feed, ownCallId)
  const syntheticRunning = Boolean(call && call.origin !== 'feed' && !call.ended)

  useEffect(() => {
    if (!synthetic || autoplayed.current) return
    autoplayed.current = true
    // Drop the flag, so a reload does not replay it.
    history.replaceState(null, '', '#/call')
    void play()
  }, [synthetic, play])

  const buyerCallId = ownCallId ?? (call && !call.ended && call.origin === 'feed' ? call.callId : '')
  const buyerReady = Boolean(buyerCallId)
  const phone = usePhoneJoin(buyerCallId, lan)
  const [buyerInThisTab, setBuyerInThisTab] = useState(false)
  const buyerLeg = call ? legByRole(call, 'buyer') : undefined
  const buyerDocked = buyer.state.status === 'live' || buyer.state.status === 'connecting' || buyer.state.status === 'reconnecting'
  const showPhoneCard = phone.kind === 'ready' && !buyerInThisTab && !buyerDocked && (!buyerLeg || Boolean(buyerLeg.ended))
  const syntheticShown = Boolean(call && call.origin !== 'feed')
  const mode = syntheticShown
    ? { text: 'Synthetic call, no speech', tone: 'badge-warn' as const }
    : { text: 'Live browser call', tone: call && !call.ended ? ('badge-ok' as const) : ('' as const) }

  return (
    <Stage
      title="Try a call"
      mode={mode}
      extra={call && <CallClock call={call} />}
      actions={
        <>
          <FeedStatusLabel status={status} />
          <button type="button" className="btn-quiet" disabled={starting || syntheticRunning || onCall} onClick={() => void play()}>
            <PlayIcon /> {syntheticRunning ? 'Playing…' : 'Play a synthetic call'}
          </button>
        </>
      }
      dealCall={call}
      footer={failed && <p role="alert" className="text-sm font-medium text-bad">The backend did not answer, so calls cannot start. A synthetic call still plays.</p>}
    >
      <CallBar call={call} />
      <SpeechCredit mode={creditMode(false, call?.origin ?? null)} />
      <Timeline call={call} empty={<Intro onSynthetic={() => void play()} busy={starting} />} />
      <div className="grid border-t border-line bg-page/60 md:grid-cols-2 md:divide-x md:divide-line">
        <div className="p-3 md:h-[10.25rem]">
          <LegDock role="farmer" sim={farmer} config={config} lines={lines} callId="" canStart={!syntheticRunning && status !== 'offline'} lockedReason={syntheticRunning ? 'Wait for the synthetic call to finish.' : 'The engine is offline, so calls cannot start.'} />
        </div>
        <div className="border-t border-line p-3 md:h-[10.25rem] md:border-t-0">
          {showPhoneCard ? (
            <PhoneJoinCard hosts={phone.hosts} port={phone.port} callId={buyerCallId} onUseThisTab={() => setBuyerInThisTab(true)} />
          ) : (
            <LegDock role="buyer" sim={buyer} config={config} lines={lines} callId={buyerCallId} canStart={buyerReady} lockedReason="Opens once a farmer is on a call." hint={phone.kind === 'ready' ? 'Or play the buyer from this tab.' : phone.hint} />
          )}
        </div>
      </div>
    </Stage>
  )
}
