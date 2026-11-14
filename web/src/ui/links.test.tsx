import { afterAll, beforeAll, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { parseRoute } from '../lib/route'
import LandingPage from '../views/LandingPage'
import Shell, { MobileMenu } from './Shell'

const hrefs = (html: string) => [...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1])
const texts = (html: string) => html.replace(/<[^>]+>/g, '\n').split('\n').map((t) => t.trim()).filter(Boolean)

const PAGES = ['Demo', 'Try a call', 'Farmers', 'Analytics', 'Buyer directory', 'Deal map']
const shell = (hash: string) => renderToStaticMarkup(<Shell route={parseRoute(hash)}><p>page</p></Shell>)
const navBlock = (html: string, label: string) => {
  const start = html.search(new RegExp(`<nav[^>]* aria-label="${label}"`))
  expect(start).toBeGreaterThanOrEqual(0)
  return html.slice(start, html.indexOf('</nav>', start) + 6)
}

describe('Shell navigation', () => {
  const html = shell('#/farmers')

  it('shows all six pages in the top bar, in order, and no How it works item', () => {
    expect(texts(navBlock(html, 'Main'))).toEqual(PAGES)
    expect(hrefs(navBlock(html, 'Main'))).toEqual(['#/demo', '#/call', '#/farmers', '#/analytics', '#/buyers', '#/deal-map'])
    expect(html).not.toContain('How it works')
    expect(hrefs(html)).not.toContain('#/how')
  })

  it('has no Advanced menu, heading or note anywhere', () => {
    expect(html).not.toMatch(/advanced/i)
    expect(html).not.toContain('aria-haspopup')
  })

  it('lists the same six pages, in order, in the mobile menu, with no Advanced heading', () => {
    const menu = renderToStaticMarkup(<MobileMenu route={parseRoute('#/buyers')} onClose={() => {}} />)
    expect(texts(navBlock(menu, 'Mobile'))).toEqual([...PAGES, 'Try the demo'])
    expect(menu).not.toMatch(/advanced/i)
    expect(menu).toContain('href="#/buyers" aria-current="page"')
  })

  it('marks the current page, including the pages that used to sit under a menu', () => {
    expect(html).toContain('href="#/farmers" aria-current="page"')
    expect(shell('#/call')).toContain('href="#/call" aria-current="page"')
    for (const view of ['analytics', 'buyers', 'deal-map']) {
      const page = shell(`#/${view}`)
      expect(navBlock(page, 'Main')).toContain(`href="#/${view}" aria-current="page"`)
      expect(page.match(/aria-current="page"/g)).toHaveLength(1)
    }
  })

  it('keeps the Try the demo button, the theme button, and the language button on the translated pages', () => {
    expect(html).toContain('Try the demo')
    expect(html).toMatch(/Switch to (dark|light) theme/)
    expect(html).not.toContain('Switch console language')
    for (const view of ['analytics', 'buyers', 'memory', 'simulator']) expect(shell(`#/${view}`)).toContain('Switch console language')
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
