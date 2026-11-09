import { describe, expect, it } from 'bun:test'
import { PcmStreamResampler } from './resample'

function voiced(count: number, rate: number): Int16Array {
  const out = new Int16Array(count)
  let phase = 0
  for (let i = 0; i < count; i++) {
    phase += (2 * Math.PI * (210 + 30 * Math.sin((2 * Math.PI * 4.3 * i) / rate))) / rate
    let v = 0
    for (let h = 1; h <= 8; h++) v += Math.sin(h * phase) / h
    out[i] = Math.round(v * 9000)
  }
  return out
}

function concat(parts: Float32Array[]): Float32Array {
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

function run(input: Int16Array, chunkSizes: number[], from: number, to: number): Float32Array {
  const r = new PcmStreamResampler(from, to)
  const parts: Float32Array[] = []
  let at = 0
  for (let c = 0; at < input.length; c++) {
    const end = Math.min(input.length, at + chunkSizes[c % chunkSizes.length])
    parts.push(r.push(input.subarray(at, end)))
    at = end
  }
  parts.push(r.flush())
  return concat(parts)
}

describe('PcmStreamResampler', () => {
  const input = voiced(4000, 8000)
  const whole = run(input, [input.length], 8000, 48000)

  it('gives identical output however the input is cut', () => {
    for (const sizes of [[1], [7], [160], [1, 2, 3, 5, 8, 13], [1280, 3, 641]]) {
      expect(Array.from(run(input, sizes, 8000, 48000))).toEqual(Array.from(whole))
    }
  })

  it('keeps every chunk seam as smooth as the rest of the voice', () => {
    const out = run(input, [160], 8000, 48000)
    const steps = out.slice(1).map((v, i) => Math.abs(v - out[i]))
    const seams = new Float32Array(steps.filter((_, i) => (i + 1) % 960 === 0))
    const others = new Float32Array(steps.filter((_, i) => (i + 1) % 960 !== 0))
    expect(Math.max(...seams)).toBeLessThanOrEqual(Math.max(...others))
  })

  it('does not fall behind or run ahead of the rate', () => {
    expect(whole.length).toBe(input.length * 6)
    expect(Math.abs(run(input, [97], 8000, 44100).length - (input.length * 44100) / 8000)).toBeLessThan(2)
  })

  it('passes samples through untouched when the rates match', () => {
    const r = new PcmStreamResampler(24000, 24000)
    const out = r.push(new Int16Array([0, 16384, -32768]))
    expect(Array.from(out)).toEqual([0, 0.5, -1])
    expect(r.flush().length).toBe(0)
  })

  it('starts a fresh stream after a flush', () => {
    const r = new PcmStreamResampler(8000, 48000)
    const first = concat([r.push(input), r.flush()])
    const second = concat([r.push(input), r.flush()])
    expect(Array.from(second)).toEqual(Array.from(first))
  })
})
