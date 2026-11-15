import { afterAll, beforeAll, describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { parseRoute } from '../lib/route'
import LandingPage from '../views/LandingPage'
import Shell, { MobileMenu } from './Shell'

const hrefs = (html: string) => [...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1])
const texts = (html: string) => html.replace(/<[^>]+>/g, '\n').split('\n').map((t) => t.trim()).filter(Boolean)

const PAGES = ['Conversation', 'Try a call', 'Farmers', 'Analytics', 'Buyer directory', 'Deal map']
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
    expect(texts(navBlock(menu, 'Mobile'))).toEqual([...PAGES, 'Watch a call'])
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

  it('keeps the Watch a call button, the theme button, and the language button on the translated pages', () => {
    expect(html).toContain('Watch a call')
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

  it('sends the hero to the demo first, then the browser call, and keeps Learn how it works', () => {
    const links = hrefs(html)
    expect(links.slice(0, 3)).toEqual(['#/demo', '#/call', '#/how'])
    expect(html).toContain('Watch a call')
    expect(html).toContain('Try a call')
    expect(html).toContain('Learn how it works')
  })

  it('shows the problem in numbers as sourced context, with no footnote sentence and no claimed results', () => {
    expect(hrefs(html).filter((h) => h.startsWith('https://www.pib.gov.in/'))).toHaveLength(6)
    expect(html).toContain('The problem in numbers.')
    expect(html).toContain('each with its year and source')
    expect(html).not.toContain('Context about the problem, not results achieved by G-One.')
    expect(html).not.toMatch(/real[- ]time|instant|guarantee|all languages|farmers helped|scams prevented/i)
  })

  it('pairs each of the five points with one call-scene pane and starts on the first point alone', () => {
    const points = [...html.matchAll(/<li[^>]*data-point="(\d+)"[^>]*>/g)]
    expect(points.map((m) => Number(m[1]))).toEqual([0, 1, 2, 3, 4])
    expect([...html.matchAll(/data-pane="\d+"/g)]).toHaveLength(points.length)
    expect(html).toContain('One call. Five things happen.')
    // Exactly one point is current (and so visible); the others stay in the markup for screen readers.
    expect(points.filter((m) => m[0].includes('data-state="current"')).map((m) => Number(m[1]))).toEqual([0])
    expect(points.filter((m) => m[0].includes('aria-current="step"')).map((m) => Number(m[1]))).toEqual([0])
    for (const m of points) expect(m[0]).not.toMatch(/aria-hidden|hidden=""|inert/)
  })

  it('builds the points up in order: the later four wait as upcoming, each with its place in the rail', () => {
    const points = [...html.matchAll(/<li[^>]*data-point="(\d+)"[^>]*>/g)]
    expect(points.map((m) => m[0].match(/data-state="(\w+)"/)?.[1])).toEqual(['current', 'upcoming', 'upcoming', 'upcoming', 'upcoming'])
    // The rail between the numbers reads each point's place from --i.
    expect(points.map((m) => m[0].match(/--i:(\d+)/)?.[1])).toEqual(['0', '1', '2', '3', '4'])
  })

  it('shows no step counters beside the points or on the call scene', () => {
    const how = html.slice(html.indexOf('id="how"'), html.indexOf('What each step rests on.'))
    expect(how).not.toMatch(/Point \d of \d|\d of 5/)
  })

  it('runs the hero the full width, not as an inset card, with no empty block under it', () => {
    const hero = html.match(/<section[^>]*>/)?.[0] ?? ''
    expect(hero).toContain('home-hero')
    expect(hero).not.toContain('home-stage')
    expect(html).not.toMatch(/<dl[^>]*>\s*<\/dl>/)
  })

  it('lets scroll alone drive the points: no step buttons, and no extra scroll room until the stage is pinned', () => {
    const how = html.slice(html.indexOf('id="how"'), html.indexOf('What each step rests on.'))
    expect(how).not.toContain('<button')
    expect(how).not.toMatch(/Previous|Next point|Show step/)
    // Server markup starts stacked (first point only); the script measures the window before it pins or lists.
    const track = how.match(/<div[^>]*class="home-track[^"]*"[^>]*>/)?.[0]
    expect(track).toContain('data-mode="stacked"')
    expect(how).not.toContain('data-spacer')
  })

  it('carries on from the five points: each capability names the step it sits behind, in the order the call reaches them', () => {
    const after = html.slice(html.indexOf('What each step rests on.'), html.indexOf('Two languages, one deal.'))
    expect(html.indexOf('What each step rests on.')).toBeGreaterThan(html.indexOf('id="how"'))
    expect(html).not.toContain('Support for the farmer throughout the call.')
    expect([...after.matchAll(/Behind (every step|step \d)/g)].map((m) => m[1])).toEqual(['every step', 'step 2', 'step 3', 'step 4', 'step 4', 'step 5'])
  })

  it('separates the buyer agreeing to join from both sides agreeing to the terms', () => {
    expect(html).toContain('This yes is to the call, not to a price.')
    expect(html).toContain('Agreeing to talk is not agreeing to a deal.')
    expect(html).toContain('A deal summary is made only when each says yes to them.')
    expect(html).not.toContain('Two explicit yeses')
    expect(html).not.toContain('The buyer must say yes')
  })

  it('ties memory to the farmer’s permission and a recognisable number, without claiming nothing is saved', () => {
    expect(html).toContain('With the farmer’s permission, and when their number can be recognised, G-One can keep a short profile and a gist of the call')
    expect(html).toContain('Saying “forget me” on a call deletes it.')
    expect(html).toContain('When the number is withheld, nothing is remembered about the caller.')
    expect(html).toContain('If the farmer agreed to be remembered on an earlier call, G-One can greet them by name')
    expect(html).not.toMatch(/nothing is saved|gist of each call|never starts over|greets a returning caller by name/)
  })

  it('drops the phone-line and test-count status block but keeps the planned phone line in the hero', () => {
    expect(html).not.toMatch(/Automated tests|backend and \d+ web/)
    expect(html).toContain('Try it in your browser; a phone line is planned.')
  })

  it('never links the live call to the old combined page', () => {
    expect(hrefs(html).filter((h) => h.startsWith('#/demo') && h !== '#/demo' && h !== '#/demo?autoplay=1')).toEqual([])
  })
})
