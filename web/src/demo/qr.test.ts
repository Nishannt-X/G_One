import { describe, expect, it } from 'bun:test'
import { qrPath } from './phoneJoin'

describe('qrPath', () => {
  const url = 'https://172.20.10.4:5173/#/join?call=call-123e4567-e89b-12d3-a456-426614174000'
  it('gives the same code for the same link', () => {
    expect(qrPath(url)).toEqual(qrPath(url))
  })
  it('gives a different code for another call', () => {
    expect(qrPath(url).path).not.toBe(qrPath(`${url}x`).path)
  })
  it('stays small enough to scan from a laptop screen', () => {
    expect(qrPath(url).size).toBeLessThanOrEqual(45)
  })
})
