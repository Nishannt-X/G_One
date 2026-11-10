import { describe, expect, it, mock } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { initialFeed } from '../lib/callState'

// Vite turns the worklet into a URL; bun has no such loader, and rendering never starts audio.
mock.module('../simulator/capture.worklet.js?worker&url', () => ({ default: '' }))
const { default: CallPage } = await import('./CallPage')

describe('Try a call page', () => {
  const html = renderToStaticMarkup(<CallPage feed={initialFeed} status="connected" lan={null} />)

  it('is labelled as a live browser call', () => {
    expect(html).toContain('data-testid="page-mode"')
    expect(html).toContain('Live browser call')
  })

  it('offers no synthetic call and no phone-number field', () => {
    expect(html).not.toMatch(/synthetic call\b/i)
    expect(html).not.toMatch(/type="tel"/)
  })

  it('keeps the farmer call button and the deal', () => {
    expect(html).toContain('Call as farmer')
    expect(html).toContain('aria-label="Deal"')
  })
})
