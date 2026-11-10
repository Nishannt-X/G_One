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

const { default: CallerCards } = await import('./CallerCards')
const { callerCard } = await import('../lib/useSimulatorSetup')

describe('demo farmer cards', () => {
  const callers = [
    { number: '+919876543210', label: 'Caller A, 98765 43210' },
    { number: '+919876543211', label: 'Suresh, onion · Lasalgaon, 98765 43211' },
  ]
  const html = renderToStaticMarkup(<CallerCards callers={callers} value="+919876543211" onChange={() => {}} />)

  it('is one radio group with a card per caller plus withheld', () => {
    expect(html).toContain('role="radiogroup"')
    expect(html.match(/type="radio"/g)).toHaveLength(3)
    expect(html).toContain('Withheld')
    expect(html).toContain('No memory')
  })

  it('shows only the masked number, never the full one', () => {
    // The radio value carries the synthetic number, as the dropdown's option did; the visible text never does.
    const visible = html.replace(/<[^>]+>/g, ' ')
    expect(visible).toContain('••210')
    expect(visible).not.toMatch(/98765|9876543/)
  })

  it('shows the detail the label carries and marks the chosen caller', () => {
    expect(html).toContain('onion · Lasalgaon · ••211')
    expect(html).toMatch(/checked="" value="\+919876543211"/)
  })
})

describe('callerCard', () => {
  it('splits a two-part label into a name and a masked number', () => {
    expect(callerCard('Caller A, 98765 43210')).toEqual({ name: 'Caller A', detail: null, masked: '••210' })
  })
  it('keeps the middle of a longer label as detail', () => {
    expect(callerCard('Suresh, onion, Nashik, 98765 43212')).toEqual({ name: 'Suresh', detail: 'onion, Nashik', masked: '••212' })
  })
  it('adds nothing to a label with no number', () => {
    expect(callerCard('Caller Z')).toEqual({ name: 'Caller Z', detail: null, masked: null })
  })
})
