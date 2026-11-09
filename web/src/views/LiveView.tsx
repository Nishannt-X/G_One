import { lazy, Suspense, useCallback, useState, type Dispatch } from 'react'
import DealCard from '../live/DealCard'
import CallHeader from '../live/CallHeader'
import LatencyHud from '../live/LatencyHud'
import MemoryGlance from '../live/MemoryGlance'
import PriceMeter from '../live/PriceMeter'
import Stage from '../live/Stage'
import Subtitles from '../live/Subtitles'
import ToolsCard from '../live/ToolsCard'
import WhisperPanel from '../live/WhisperPanel'
import { pickCall, type CallView, type FeedAction, type FeedState } from '../lib/callState'
import { useI18n } from '../lib/i18n'
import type { FeedStatus } from '../lib/useConsoleFeed'
import { AlertIcon, CheckIcon, MicIcon, PhoneIcon } from '../ui/icons'
import SampleCallButton from '../ui/SampleCallButton'

// The dev tools are loaded only when `import.meta.env.DEV` is true; the bundler removes this branch from production.
const DevTools = import.meta.env.DEV ? lazy(() => import('../dev/DevTools')) : null

function EndReasonBanner({ call }: { call: CallView }) {
  const { t } = useI18n()
  const reason = call.ended?.reason || call.legs.find((l) => l.ended)?.ended?.reason
  if (!reason) return null

  const isAgreed = reason === 'deal_agreed'
  const isDeclined = reason === 'buyer_declined'
  const isLeft = reason === 'farmer_left'

  const text = isAgreed
    ? t('reason_deal_agreed')
    : isDeclined
      ? t('reason_buyer_declined')
      : isLeft
        ? t('reason_farmer_left')
        : t('reason_call_ended', { reason })

  const bannerClass = isAgreed
    ? 'border-ok bg-ok-soft text-ok'
    : isDeclined
      ? 'border-bad bg-bad-soft text-bad'
      : 'border-warn bg-warn-soft text-warn'

  const Icon = isAgreed ? CheckIcon : AlertIcon

  return (
    <div
      role="status"
      className={`flex items-center gap-3 rounded-xl border p-4 font-bold shadow-card ${bannerClass}`}
    >
      <Icon className="text-xl shrink-0" />
      <p className="text-base">{text}</p>
    </div>
  )
}

function Waiting({ status, onStarted }: { status: FeedStatus; onStarted: () => void }) {
  const { t } = useI18n()
  const offline = status === 'offline'
  return (
    <section className="card animate-rise px-5 py-12 text-center md:py-16">
      <div className={`mx-auto grid h-16 w-16 place-items-center rounded-full text-3xl ${offline ? 'bg-bad-soft text-bad' : 'bg-farmer-soft text-farmer'}`}>
        {offline ? <AlertIcon /> : <PhoneIcon />}
      </div>
      <h1 className="mt-4 text-3xl font-bold md:text-4xl">{t(offline ? 'waiting_offline' : 'waiting_title')}</h1>
      <p className="mx-auto mt-2 max-w-xl text-lg text-muted">{t(offline ? 'waiting_offline_body' : 'waiting_body')}</p>
      <div className="mx-auto mt-6 flex max-w-xl flex-col items-center gap-3">
        <SampleCallButton onStarted={onStarted} />
        <a className="btn-quiet" href="#/simulator"><MicIcon /> {t('open_simulator')}</a>
      </div>
    </section>
  )
}

export default function LiveView({ feed, status, dispatch }: { feed: FeedState; status: FeedStatus; dispatch: Dispatch<FeedAction> }) {
  const { t } = useI18n()
  const [pinned, setPinned] = useState<string | null>(null)
  const call = pickCall(feed, pinned)
  const follow = useCallback(() => setPinned(null), [])
  return (
    <div className="space-y-4">
      {call ? (
        <>
          <CallHeader call={call} calls={feed.order.map((id) => feed.calls[id])} onPick={setPinned} />
          <EndReasonBanner call={call} />
          {call.origin !== 'feed' && (
            <p role="note" className="rounded-xl border border-dashed border-whisper-edge bg-whisper-soft px-4 py-2 font-semibold text-whisper">
              {t(call.origin === 'mock' ? 'banner_mock' : 'banner_sample')}
            </p>
          )}
          <Stage call={call} />
          <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_23rem]">
            <Subtitles call={call} />
            <div className="space-y-4">
              <WhisperPanel call={call} />
              <DealCard call={call} />
            </div>
          </div>
          <div className="grid items-start gap-4 md:grid-cols-2 xl:grid-cols-4">
            <PriceMeter call={call} />
            <LatencyHud call={call} />
            <MemoryGlance call={call} />
            <ToolsCard call={call} />
          </div>
          {call.ended && (
            <div className="flex justify-center pt-2"><SampleCallButton className="btn-quiet" onStarted={follow} /></div>
          )}
        </>
      ) : (
        <Waiting status={status} onStarted={follow} />
      )}
      {DevTools && (
        <Suspense fallback={null}>
          <DevTools emit={(event) => dispatch({ type: 'event', event, mock: true })} />
        </Suspense>
      )}
    </div>
  )
}
