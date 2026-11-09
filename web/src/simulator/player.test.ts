import { describe, expect, it } from 'bun:test'
import { PcmPlayer } from './player'

class FakeBuffer {
  data: Float32Array
  sampleRate: number
  constructor(length: number, sampleRate: number) {
    this.data = new Float32Array(length)
    this.sampleRate = sampleRate
  }
  copyToChannel(source: Float32Array) {
    this.data.set(source)
  }
}

class FakeContext {
  state = 'running'
  currentTime = 0
  sampleRate: number
  started: { buffer: FakeBuffer; at: number; connected: unknown }[] = []
  constructor(sampleRate: number) {
    this.sampleRate = sampleRate
  }
  resume() {
    return Promise.resolve()
  }
  createBuffer(_channels: number, length: number, rate: number) {
    return new FakeBuffer(length, rate)
  }
  createBufferSource() {
    const started = this.started
    const node = {
      buffer: null as FakeBuffer | null,
      connected: null as unknown,
      onended: null as (() => void) | null,
      connect(d: unknown) {
        node.connected = d
      },
      disconnect() {},
      start(at: number) {
        started.push({ buffer: node.buffer!, at, connected: node.connected })
      },
      stop() {},
    }
    return node
  }
}

const DESTINATION = { name: 'destination' }

function player(contextRate: number, wireRate: number) {
  const context = new FakeContext(contextRate)
  return { context, player: new PcmPlayer(context as unknown as AudioContext, DESTINATION as unknown as AudioNode, wireRate) }
}

function ramp(from: number, count: number): ArrayBuffer {
  const out = new Int16Array(count)
  for (let i = 0; i < count; i++) out[i] = (from + i) % 20000
  return out.buffer
}

describe('PcmPlayer', () => {
  it('plays wideband audio sample for sample, with no filter or gain in the path', () => {
    const { context, player: p } = player(24000, 24000)
    const chunks = [480, 480, 3840, 480]
    let at = 0
    for (const size of chunks) {
      p.enqueue(ramp(at, size))
      at += size
    }
    p.whenPlayed(() => {})

    expect(context.started.every((s) => s.connected === DESTINATION)).toBe(true)
    const all = context.started.flatMap((s) => Array.from(s.buffer.data))
    expect(all.length).toBe(at)
    const samples = Array.from(new Int16Array(ramp(0, at)))
    for (let i = 32; i < all.length - 32; i++) expect(Math.abs(all[i] - samples[i] / 32768)).toBeLessThan(1e-6)
  })

  it('chains blocks with no gap and no overlap on the audio clock', () => {
    const { context, player: p } = player(24000, 24000)
    for (let i = 0; i < 40; i++) p.enqueue(ramp(i * 480, 480))
    p.whenPlayed(() => {})

    for (let b = 1; b < context.started.length; b++) {
      const prev = context.started[b - 1]
      expect(context.started[b].at).toBeCloseTo(prev.at + prev.buffer.data.length / 24000, 9)
    }
  })

  it('honours the rate the server states by resampling to the context', () => {
    const { context, player: p } = player(48000, 24000)
    p.setWireRate(8000)
    p.enqueue(ramp(0, 1600))
    p.whenPlayed(() => {})
    const total = context.started.reduce((n, s) => n + s.buffer.data.length, 0)
    expect(total).toBe(1600 * 6)
    expect(context.started[0].buffer.sampleRate).toBe(48000)
  })
})
