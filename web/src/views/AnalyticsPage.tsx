import { useEffect, type Dispatch } from 'react'
import { tabOf, type AnalyticsTab } from '../lib/analytics'
import type { FeedAction, FeedState } from '../lib/callState'
import type { FeedStatus } from '../lib/useConsoleFeed'
import CallsView from './CallsView'
import LiveView from './LiveView'

const TABS: { tab: AnalyticsTab; href: string; label: string }[] = [
  { tab: 'live', href: '#/analytics', label: 'Live' },
  { tab: 'calls', href: '#/analytics/calls', label: 'Calls' },
]

/** Analytics: the live view of the current call, and the calls kept with consent. */
export default function AnalyticsPage({ feed, status, dispatch, param, query }: { feed: FeedState; status: FeedStatus; dispatch: Dispatch<FeedAction>; param: string | null; query: URLSearchParams }) {
  const tab = tabOf(param)
  useEffect(() => {
    const before = document.title
    document.title = 'Analytics · G-One'
    return () => {
      document.title = before
    }
  }, [])
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Analytics</h1>
        <nav aria-label="Analytics" className="grid grid-flow-col gap-1 rounded-xl bg-sunk p-1">
          {TABS.map((t) => (
            <a key={t.tab} href={t.href} aria-current={tab === t.tab ? 'page' : undefined} className={`flex min-h-11 items-center justify-center rounded-lg px-4 font-semibold transition-colors ${tab === t.tab ? 'bg-surface text-ink shadow-card' : 'text-muted hover:text-ink'}`}>
              {t.label}
            </a>
          ))}
        </nav>
      </div>
      {tab === 'live' ? <LiveView feed={feed} status={status} dispatch={dispatch} /> : <CallsView callId={query.get('id')} />}
    </div>
  )
}
