import { useMemo, useState } from 'react'
import { legByRole, pickCall, type FeedState } from '../lib/callState'
import type { FeedStatus } from '../lib/useConsoleFeed'
import { useDemoLines, useSimulatorConfig } from '../lib/useSimulatorSetup'
import { useCallSimulator } from '../simulator/useCallSimulator'
import { ChevronIcon } from '../ui/icons'
import CallBar, { CallClock, FeedStatusLabel } from './CallBar'
import DealPanel from './DealPanel'
import LegDock from './LegDock'
import PhoneJoinCard from './PhoneJoinCard'
import SpeechCredit from './SpeechCredit'
import Timeline from './Timeline'
import { creditMode } from './creditMode'
import { usePhoneJoin } from './phoneJoin'

function Intro() {
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 py-10 text-center">
      <h2 className="text-2xl leading-tight font-semibold">No call yet</h2>
      <p className="mt-2 max-w-md text-muted">Start a call as the farmer above, then speak or type. Once you are on the call, a buyer can join from a phone or from this tab.</p>
    </div>
  )
}

/** Only calls from the live feed belong on this page: scripted and mock calls have their own pages. */
function liveOnly(feed: FeedState): FeedState {
  const order = feed.order.filter((id) => feed.calls[id].origin === 'feed')
  return order.length === feed.order.length ? feed : { order, calls: Object.fromEntries(order.map((id) => [id, feed.calls[id]])) }
}

interface Props {
  feed: FeedState
  status: FeedStatus
  lan: string | null
}

/** The browser call, as a working tool: the farmer on this tab's microphone, the buyer on a phone or this tab, G-One between. */
export default function CallPage({ feed, status, lan }: Props) {
  const farmer = useCallSimulator()
  const buyer = useCallSimulator()
  const { config, failed } = useSimulatorConfig()
  const lines = useDemoLines()
  const live = useMemo(() => liveOnly(feed), [feed])

  const onCall = farmer.state.status === 'live' || farmer.state.status === 'connecting' || farmer.state.status === 'reconnecting'
  // Follow this tab's call while it runs; otherwise the newest live call.
  const ownCallId = onCall ? farmer.state.callId : null
  const call = pickCall(live, ownCallId)

  const buyerCallId = ownCallId ?? (call && !call.ended ? call.callId : '')
  const buyerReady = Boolean(buyerCallId)
  const phone = usePhoneJoin(buyerCallId, lan)
  const [buyerInThisTab, setBuyerInThisTab] = useState(false)
  const buyerLeg = call ? legByRole(call, 'buyer') : undefined
  const buyerDocked = buyer.state.status === 'live' || buyer.state.status === 'connecting' || buyer.state.status === 'reconnecting'
  const showPhoneCard = phone.kind === 'ready' && !buyerInThisTab && !buyerDocked && (!buyerLeg || Boolean(buyerLeg.ended))
  const running = Boolean(call && !call.ended)

  return (
    <div className="mx-auto max-w-[1180px] px-4 py-6 sm:px-6 lg:py-8">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-3xl font-semibold">Try a call</h1>
            <span data-testid="page-mode" className={`badge ${running ? 'badge-ok' : ''}`}>Live browser call</span>
          </div>
          <p className="mt-1 text-muted">Talk to G-One from this browser. No phone number is dialled.</p>
        </div>
        <div className="flex items-center gap-4 text-sm">
          {call && <span className="text-muted">Call time <CallClock call={call} /></span>}
          <FeedStatusLabel status={status} />
        </div>
      </header>

      <div className="mt-6 grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20.5rem]">
        <div className="min-w-0 space-y-5">
          <section aria-labelledby="call-box" className="card p-4 sm:p-5">
            <h2 id="call-box" className="mb-3 text-sm font-bold tracking-wide text-muted">You, as the farmer</h2>
            <LegDock role="farmer" sim={farmer} config={config} lines={lines} callId="" canStart={status !== 'offline'} lockedReason="The engine is offline, so calls cannot start." />
            {failed && <p role="alert" className="mt-3 text-sm font-medium text-bad">The backend did not answer, so calls cannot start.</p>}
          </section>

          <section aria-label="Conversation" className="card flex h-[34rem] min-w-0 flex-col overflow-hidden lg:h-[clamp(30rem,calc(100dvh-24rem),44rem)]">
            <CallBar call={call} />
            <SpeechCredit mode={creditMode(false, call?.origin ?? null)} />
            <Timeline call={call} empty={<Intro />} />
          </section>
        </div>

        <div className="min-w-0 space-y-5">
          <DealPanel call={call} compact />
          <section aria-labelledby="buyer-box" className="card p-4">
            {showPhoneCard ? (
              <details open className="group">
                <summary className="flex cursor-pointer items-center justify-between gap-2 rounded-lg">
                  <h2 id="buyer-box" className="text-sm font-bold tracking-wide text-muted">Buyer joins from a phone</h2>
                  <ChevronIcon aria-hidden="true" className="text-muted transition-transform group-open:rotate-180" />
                </summary>
                <div className="mt-3 h-36">
                  <PhoneJoinCard hosts={phone.hosts} port={phone.port} callId={buyerCallId} onUseThisTab={() => setBuyerInThisTab(true)} />
                </div>
              </details>
            ) : (
              <>
                <h2 id="buyer-box" className="mb-3 text-sm font-bold tracking-wide text-muted">The buyer</h2>
                <LegDock role="buyer" sim={buyer} config={config} lines={lines} callId={buyerCallId} canStart={buyerReady} lockedReason="Opens once a farmer is on a call." hint={phone.kind === 'ready' ? 'Or play the buyer from this tab.' : phone.hint} />
              </>
            )}
          </section>
        </div>
      </div>
    </div>
  )
}
