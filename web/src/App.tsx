import { useEffect } from 'react'
import { parseRoute, redirectFor, useHash, type Route } from './lib/route'
import { useFarmerRegistry } from './lib/farmers'
import { useConsoleFeed } from './lib/useConsoleFeed'
import Shell from './ui/Shell'
import CallPage from './demo/CallPage'
import DemoPage from './demo/DemoPage'
import SampleReceiptPage from './demo/voiced/SampleReceiptPage'
import BuyersView from './views/BuyersView'
import FarmersPage from './views/FarmersPage'
import LandingPage from './views/LandingPage'
import AnalyticsPage from './views/AnalyticsPage'
import MemoryView from './views/MemoryView'
import JoinPage from './simulator/JoinPage'
import SimulatorPage from './simulator/SimulatorPage'

/** The older operator views share one width; the new pages lay themselves out. */
function Legacy({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto max-w-[1366px] px-4 py-6 sm:px-6 md:py-8 lg:px-8">{children}</div>
}

export default function App() {
  const hash = useHash()
  const route = parseRoute(hash)
  // An old link (the live call on #/demo, or #/live) already renders the right page; this fixes the address to match.
  useEffect(() => {
    const target = redirectFor(hash)
    if (target) history.replaceState(null, '', target)
  }, [hash])
  // A buyer's phone never opens the console feed: it carries the farmer's whispers and memory.
  if (route.view === 'sample-receipt') return <SampleReceiptPage />
  return route.view === 'join' ? <JoinPage query={route.query} /> : <Console route={route} />
}

function Console({ route }: { route: Route }) {
  const { feed, status, dispatch } = useConsoleFeed()
  useFarmerRegistry(feed)

  return (
    <Shell route={route}>
      {route.view === 'home' ? (
        <LandingPage section={route.param} />
      ) : route.view === 'demo' ? (
        <DemoPage autoplay={route.query.get('autoplay') === '1'} />
      ) : route.view === 'call' ? (
        <CallPage feed={feed} status={status} lan={route.query.get('lan')} />
      ) : route.view === 'farmers' ? (
        <FarmersPage openKey={route.param} seed={route.query.get('seed') === '1'} />
      ) : route.view === 'analytics' ? (
        <Legacy><AnalyticsPage feed={feed} status={status} dispatch={dispatch} param={route.param} query={route.query} /></Legacy>
      ) : route.view === 'buyers' ? (
        <Legacy><BuyersView /></Legacy>
      ) : route.view === 'memory' ? (
        <Legacy><MemoryView initialKey={route.param} /></Legacy>
      ) : (
        <SimulatorPage />
      )}
    </Shell>
  )
}
