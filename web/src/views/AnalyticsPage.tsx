import { useEffect, type Dispatch } from 'react'
import type { FeedAction, FeedState } from '../lib/callState'
import type { FeedStatus } from '../lib/useConsoleFeed'
import LiveView from './LiveView'

/** Analytics: the live view of the current call. */
export default function AnalyticsPage({ feed, status, dispatch }: { feed: FeedState; status: FeedStatus; dispatch: Dispatch<FeedAction> }) {
  useEffect(() => {
    const before = document.title
    document.title = 'Analytics · G-One'
    return () => {
      document.title = before
    }
  }, [])
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Analytics</h1>
      <LiveView feed={feed} status={status} dispatch={dispatch} />
    </div>
  )
}
