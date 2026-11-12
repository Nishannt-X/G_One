import { afterAll, beforeAll, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { parseRoute } from '../lib/route'
import LandingPage from '../views/LandingPage'
import LiveView from '../views/LiveView'
import { initialFeed } from '../lib/callState'
import BuyersView from '../views/BuyersView'
import MemoryView from '../views/MemoryView'
import Shell from './Shell'
import SampleCallButton from './SampleCallButton'

const hrefs = (html: string) => [...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1])
const texts = (html: string) => html.replace(/<[^>]+>/g, '\n').split('\n').map((t) => t.trim()).filter(Boolean)

describe('Shell navigation', () => {
  const html = renderToStaticMarkup(<Shell route={parseRoute('#/farmers')}><p>page</p></Shell>)

  it('shows all five primary destinations without an opaque memory lookup', () => {
    const nav = html.slice(html.indexOf('<nav aria-label="Main"'), html.indexOf('</nav>') + 6)
    expect(texts(nav).slice(0, 5)).toEqual(['Sample demo', 'Try a call', 'Farmers', 'Buyer directory', 'Analytics'])
    expect(nav).not.toContain('Memory lookup')
    expect(html).not.toContain('How it works')
    expect(hrefs(html)).not.toContain('#/how')
    expect(html).toContain('href="#/demo?autoplay=1"')
  })

  it('marks the current page', () => {
    expect(html).toContain('href="#/farmers" aria-current="page"')
    const call = renderToStaticMarkup(<Shell route={parseRoute('#/call')}><p>page</p></Shell>)
    expect(call).toContain('href="#/call" aria-current="page"')
  })
})

describe('Landing page', () => {
  let html = ''
  const hadWindow = 'window' in globalThis
  beforeAll(() => {
    // The call scene asks once, while rendering, whether motion is reduced.
    if (!hadWindow) Object.assign(globalThis, { window: { matchMedia: () => ({ matches: true, addEventListener() {}, removeEventListener() {} }) } })
    html = renderToStaticMarkup(<LandingPage section={null} />)
  })
  afterAll(() => {
    if (!hadWindow) delete (globalThis as { window?: unknown }).window
  })

  it('sends the hero to the demo first, then the browser call, and keeps See how it works', () => {
    const links = hrefs(html)
    expect(links.slice(0, 3)).toEqual(['#/demo?autoplay=1', '#/call', '#/how'])
    expect(html).toContain('Watch the demo')
    expect(html).toContain('Try a call')
    expect(html).toContain('See how it works')
  })

  it('shows the problem in numbers with a source link per figure and the context footnote', () => {
    expect(hrefs(html).filter((h) => h.startsWith('https://www.pib.gov.in/'))).toHaveLength(6)
    expect(html).toContain('Context about the problem, not results achieved by G-One. Survey figures are estimates.')
    expect(html).not.toMatch(/real[- ]time|instant|guarantee|all languages|farmers helped|scams prevented/i)
  })

  it('never links the live call to the old combined page', () => {
    expect(hrefs(html).filter((h) => h.startsWith('#/demo') && h !== '#/demo?autoplay=1')).toEqual([])
  })
})

describe('demo entry points', () => {
  it('opens the playable sample and the live browser call from Analytics', () => {
    const sample = renderToStaticMarkup(<SampleCallButton onStarted={() => {}} />)
    const live = renderToStaticMarkup(<LiveView feed={initialFeed} status="connected" dispatch={() => {}} />)
    expect(sample).toContain('href="#/demo?autoplay=1"')
    expect(live).toContain('href="#/call"')
  })

  it('links opaque caller-key lookup to the searchable farmer page', () => {
    expect(renderToStaticMarkup(<MemoryView />)).toContain('href="#/farmers"')
  })

  it('calls every buyer and price range synthetic', () => {
    const html = renderToStaticMarkup(<BuyersView />)
    expect(html).toContain('Every buyer, name and price range here is invented')
  })
})
