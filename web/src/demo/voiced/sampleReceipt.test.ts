import { describe, expect, it } from 'bun:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import manifest from '../../../public/demo/voiced-sample/manifest.json'
import { parseRoute } from '../../lib/route'
import { creditMode } from '../speechCredit'
import type { Manifest } from './manifest'
import { Phone } from './PhoneMock'
import { ReceiptCard } from './SampleReceiptPage'
import { SAMPLE_RECEIPT_HREF, sampleReceipt } from './sampleReceipt'

const sample = manifest as unknown as Manifest

describe('sample receipt', () => {
  it('links the lit phone to the sample route, never to a server receipt', () => {
    const html = renderToStaticMarkup(createElement(Phone, { party: 'farmer', manifest: sample, lit: true }))
    expect(html).toContain(`href="${SAMPLE_RECEIPT_HREF}"`)
    expect(html).not.toContain('/api/deals/')
    expect(parseRoute(SAMPLE_RECEIPT_HREF).view).toBe('sample-receipt')
  })

  it('carries the terms of the deal the sample ends on', () => {
    expect(sampleReceipt(sample)).toEqual({
      farmer: 'Basavaraj',
      buyer: 'Mondal Traders',
      commodity: 'onion',
      quantity: '40 quintal',
      price: 1900,
      unit: 'quintal',
      total: 76_000,
      market: 'Kolkata',
      pickup: "Buyer's truck, pay on delivery",
      date: '2026-11-07',
      farmerYes: true,
      buyerYes: true,
    })
  })

  it('shows the amounts and says it is a synthetic sample', () => {
    const html = renderToStaticMarkup(createElement(ReceiptCard, { receipt: sampleReceipt(sample)! }))
    expect(html).toMatch(/₹\s*76,000/)
    expect(html).toContain('40 quintal')
    expect(html).toContain('Scripted sample, synthetic')
    expect(html).toContain('not a contract')
  })
})

describe('speech credit', () => {
  it('credits only the speech each mode really uses', () => {
    expect(creditMode(true, null, false)).toBe('sample')
    expect(creditMode(true, 'feed', true)).toBe('sample')
    expect(creditMode(false, 'feed', false)).toBe('live')
    expect(creditMode(false, 'feed', true)).toBeNull()
    expect(creditMode(false, 'mock', false)).toBeNull()
    expect(creditMode(false, null, false)).toBeNull()
  })
})
