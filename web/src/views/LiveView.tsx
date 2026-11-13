import { lazy, Suspense, useCallback, useState, type Dispatch } from 'react'
import CallBoard from '../live/CallBoard'
import Subtitles from '../live/Subtitles'
import { followedCall, pinCall, type CallPin, type FeedAction, type FeedState } from '../lib/callState'
import { useI18n } from '../lib/i18n'
import type { FeedStatus } from '../lib/useConsoleFeed'
import { AlertIcon, MicIcon, PhoneIcon } from '../ui/icons'
import SampleCallButton from '../ui/SampleCallButton'

// The dev tools are loaded only when `import.meta.env.DEV` is true; the bundler removes this branch from production.
const DevTools = import.meta.env.DEV ? lazy(() => import('../dev/DevTools')) : null

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
        <a className="btn-quiet" href="#/call"><MicIcon /> {t('open_simulator')}</a>
      </div>
    </section>
  )
}

export default function LiveView({ feed, status, dispatch }: { feed: FeedState; status: FeedStatus; dispatch: Dispatch<FeedAction> }) {
  const [pin, setPin] = useState<CallPin | null>(null)
  const call = followedCall(feed, pin)
  const follow = useCallback(() => setPin(null), [])
  return (
    <div className="space-y-4">
      {call ? (
        <>
          <CallBoard call={call} calls={feed.order.map((id) => feed.calls[id])} onPick={(id) => setPin(pinCall(feed, id))} main={<Subtitles call={call} />} />
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
