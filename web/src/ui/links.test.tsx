import { afterAll, beforeAll, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { parseRoute } from '../lib/route'
import LandingPage from '../views/LandingPage'
import Shell from './Shell'

const hrefs = (html: string) => [...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1])
const texts = (html: string) => html.replace(/<[^>]+>/g, '\n').split('\n').map((t) => t.trim()).filter(Boolean)

describe('Shell navigation', () => {
  const html = renderToStaticMarkup(<Shell route={parseRoute('#/farmers')}><p>page</p></Shell>)

  it('shows Demo, Try a call and Farmers, then the Advanced menu, and no How it works item', () => {
    const nav = html.slice(html.indexOf('<nav aria-label="Main"'), html.indexOf('</nav>') + 6)
    expect(texts(nav).slice(0, 4)).toEqual(['Demo', 'Try a call', 'Farmers', 'Advanced'])
    expect(html).not.toContain('How it works')
    expect(hrefs(html)).not.toContain('#/how')
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
    expect(links.slice(0, 3)).toEqual(['#/demo', '#/call', '#/how'])
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
    expect(hrefs(html).filter((h) => h.startsWith('#/demo') && h !== '#/demo' && h !== '#/demo?autoplay=1')).toEqual([])
  })
})
