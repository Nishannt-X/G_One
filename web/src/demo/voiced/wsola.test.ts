import { describe, expect, it } from 'bun:test'
import { stretch } from '../../../scripts/wsola'

const RATE = 24_000

function sine(hz: number, seconds: number, level = 0.5): Int16Array {
  return Int16Array.from({ length: Math.round(seconds * RATE) }, (_, i) => Math.round(level * 32767 * Math.sin((2 * Math.PI * hz * i) / RATE)))
}

/** Frequency from upward zero crossings, ignoring the edges. */
function frequency(samples: Int16Array): number {
  const from = Math.round(samples.length * 0.1)
  const to = Math.round(samples.length * 0.9)
  let first = -1
  let last = -1
  let count = 0
  for (let i = from + 1; i < to; i++) {
    if (samples[i - 1] < 0 && samples[i] >= 0) {
      if (first < 0) first = i
      else count += 1
      last = i
    }
  }
  return (count * RATE) / (last - first)
}

describe('WSOLA time-stretch', () => {
  it('makes a clip 1.3 times shorter', () => {
    const input = sine(180, 2)
    expect(stretch(input, RATE, 1.3).length).toBe(Math.round(input.length / 1.3))
  })

  it('keeps silence silent', () => {
    const out = stretch(new Int16Array(RATE), RATE, 1.3)
    expect(out.length).toBe(Math.round(RATE / 1.3))
    expect(out.every((s) => s === 0)).toBe(true)
  })

  it('keeps a tone at its frequency, unlike resampling', () => {
    for (const hz of [120, 220, 310]) {
      const out = stretch(sine(hz, 1.5), RATE, 1.3)
      expect(Math.abs(frequency(out) / hz - 1)).toBeLessThan(0.02)
    }
  })

  it('keeps the level and stays under -1 dBFS', () => {
    const input = sine(200, 1, 0.95)
    const out = stretch(input, RATE, 1.3)
    expect(Math.max(...out.map(Math.abs))).toBeLessThanOrEqual(Math.round(0.89 * 32768))
    const rms = (s: Int16Array) => Math.sqrt(s.reduce((sum, v) => sum + v * v, 0) / s.length)
    const quiet = sine(200, 1, 0.3)
    expect(Math.abs(20 * Math.log10(rms(stretch(quiet, RATE, 1.3)) / rms(quiet)))).toBeLessThan(0.5)
  })

  it('returns a copy at tempo 1', () => {
    const input = sine(200, 0.2)
    expect(stretch(input, RATE, 1)).toEqual(input)
  })
})
