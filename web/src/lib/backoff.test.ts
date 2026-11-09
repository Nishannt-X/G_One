import { describe, expect, it } from 'bun:test'
import { backoffDelay } from './backoff'

describe('backoffDelay', () => {
  const options = { baseMs: 1000, maxMs: 8000, jitter: 0.2 }

  it('grows exponentially with attempts', () => {
    // Zero jitter test with deterministic random returning 0
    const d1 = backoffDelay(1, options, () => 0)
    const d2 = backoffDelay(2, options, () => 0)
    const d3 = backoffDelay(3, options, () => 0)
    const d4 = backoffDelay(4, options, () => 0)

    expect(d1).toBe(800) // 1000 - 200
    expect(d2).toBe(1600) // 2000 - 400
    expect(d3).toBe(3200) // 4000 - 800
    expect(d4).toBe(6400) // 8000 - 1600
  })

  it('caps at maxMs', () => {
    const d10 = backoffDelay(10, options, () => 1)
    expect(d10).toBeLessThanOrEqual(8000)
    expect(d10).toBe(8000)
  })

  it('applies jitter within expected bounds', () => {
    const minVal = backoffDelay(1, options, () => 0)
    const maxVal = backoffDelay(1, options, () => 1)
    const midVal = backoffDelay(1, options, () => 0.5)

    expect(minVal).toBe(800)
    expect(maxVal).toBe(1000)
    expect(midVal).toBe(900)
  })
})
