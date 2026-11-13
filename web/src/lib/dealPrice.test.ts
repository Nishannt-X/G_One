import { describe, expect, it } from 'bun:test'
import { dealPrice, rupees } from './format'
import { dealValue } from './pricing'

describe('explicit price basis', () => {
  it('treats a total as the value without multiplying by quantity', () => {
    expect(dealPrice(2000, 'total')).toBe(`${rupees(2000)} total`)
    expect(dealValue('10 kg', 2000, 'total')).toBe(2000)
    expect(dealValue('10 kg', 2000, 'kg')).toBe(20000)
  })

  it('keeps each approved per-unit format byte for byte', () => {
    expect(dealPrice(2000, 'kg')).toBe(`${rupees(2000)} / kg`)
    expect(dealPrice(2000, 'kg', ' per ')).toBe(`${rupees(2000)} per kg`)
    expect(dealPrice(2000, 'kg', '/')).toBe(`${rupees(2000)}/kg`)
    expect(dealPrice(2000, null)).toBe(rupees(2000))
    expect(dealPrice(2000, 'total', '/')).toBe(`${rupees(2000)} total`)
  })
})
