import { parseRoute, useHash } from './lib/route'
import { useFarmerRegistry } from './lib/farmers'
import { useConsoleFeed } from './lib/useConsoleFeed'
import Shell from './ui/Shell'
import DemoPage from './demo/DemoPage'
import BuyersView from './views/BuyersView'
import FarmersPage from './views/FarmersPage'
import LandingPage from './views/LandingPage'
import LiveView from './views/LiveView'
import MemoryView from './views/MemoryView'
import SimulatorPage from './simulator/SimulatorPage'

/** The older operator views share one width; the new pages lay themselves out. */
function Legacy({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto max-w-[1366px] px-4 py-6 sm:px-6 md:py-8 lg:px-8">{children}</div>
}

export default function App() {
  const { feed, status, dispatch } = useConsoleFeed()
  useFarmerRegistry(feed)
  const route = parseRoute(useHash())

  return (
    <Shell route={route}>
      {route.view === 'home' ? (
        <LandingPage section={route.param} />
      ) : route.view === 'demo' ? (
        <DemoPage feed={feed} status={status} dispatch={dispatch} autoplay={route.query.get('autoplay') === '1'} />
      ) : route.view === 'farmers' ? (
        <FarmersPage openKey={route.param} seed={route.query.get('seed') === '1'} />
      ) : route.view === 'live' ? (
        <Legacy><LiveView feed={feed} status={status} dispatch={dispatch} /></Legacy>
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
