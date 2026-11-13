import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { feedReducer, initialFeed, type FeedState } from '../lib/callState'
import type { FeedStatus } from '../lib/useConsoleFeed'
import LiveView from './LiveView'

const render = (feed: FeedState, status: FeedStatus) => renderToStaticMarkup(<LiveView feed={feed} status={status} dispatch={() => {}} />)
const buttons = (html: string) => [...html.matchAll(/<button\b[^>]*>([\s\S]*?)<\/button>/g)].map((m) => m[1].replace(/<[^>]+>/g, '').trim())

describe('Analytics Live tab', () => {
  for (const status of ['connected', 'offline'] as const) {
    it(`offers no sample call while waiting (${status}) and keeps the simulator link`, () => {
      const html = render(initialFeed, status)
      expect(html).toContain(status === 'offline' ? 'Console Offline' : 'Waiting for a Call')
      expect(html).not.toMatch(/sample/i)
      expect(buttons(html)).toEqual([])
      expect(html).toContain('href="#/simulator"')
      expect(html).toContain('Open Call Simulator')
    })
  }

  it('still shows a finished call, with no sample call button under it', () => {
    let feed: FeedState = initialFeed
    feed = feedReducer(feed, { type: 'event', event: { type: 'call_started', callId: 'c1', atMs: 1, language: 'hi-IN' } })
    feed = feedReducer(feed, { type: 'event', event: { type: 'leg_joined', callId: 'c1', atMs: 2, legId: 'f', role: 'farmer', source: 'simulator', language: 'hi-IN' } })
    feed = feedReducer(feed, { type: 'event', event: { type: 'call_ended', callId: 'c1', atMs: 3000, durationMs: 2999, reason: 'hangup' } })
    expect(feed.calls.c1.ended).toBeTruthy()

    const html = render(feed, 'connected')
    expect(html).not.toContain('Waiting for a Call')
    expect(html).not.toMatch(/sample (demo )?call/i)
    expect(buttons(html).some((label) => /sample/i.test(label))).toBe(false)
  })
})
