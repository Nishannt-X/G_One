import { PLAYBACK_RATE } from './protocol'
import { PcmStreamResampler } from './resample'

/** Cushion before the first block, absorbing network jitter so that later blocks chain without gaps. */
const INITIAL_LEAD_SECONDS = 0.14
/** Incoming 20 ms messages are gathered into blocks of this length before they are scheduled. */
const BLOCK_SECONDS = 0.16
const FLUSH_AFTER_MS = 35
const FADE_SAMPLES = 32

/**
 * Plays mono PCM16 as it streams in, chaining blocks on the audio clock. Audio that already arrives at the context's
 * rate is played as it came; only a mismatch goes through {@link PcmStreamResampler}.
 */
export class PcmPlayer {
  private readonly context: AudioContext
  private readonly destination: AudioNode

  private resampler: PcmStreamResampler
  private blockSamples = 0

  private nextStart = 0
  private isFirstBlock = true

  private pendingSamples: Int16Array[] = []
  private pendingCount = 0
  private flushTimer: ReturnType<typeof setTimeout> | null = null

  private sources = new Set<AudioBufferSourceNode>()
  private last: AudioBufferSourceNode | null = null
  private waiters: (() => void)[] = []

  constructor(context: AudioContext, destination: AudioNode, wireRate: number = PLAYBACK_RATE) {
    this.context = context
    this.destination = destination
    this.resampler = new PcmStreamResampler(wireRate, context.sampleRate)
    this.setWireRate(wireRate)
  }

  /** Sets the rate of incoming audio, as stated by the backend's ready message. */
  setWireRate(rate: number): void {
    this.resampler = new PcmStreamResampler(rate, this.context.sampleRate)
    this.blockSamples = Math.round(rate * BLOCK_SECONDS)
  }

  /** True while audio is sounding or scheduled to sound. */
  get playing(): boolean {
    return this.sources.size > 0 || this.pendingCount > 0
  }

  enqueue(pcm16le: ArrayBuffer): void {
    if (this.context.state === 'suspended') {
      void this.context.resume()
    }

    const input = new Int16Array(pcm16le, 0, Math.floor(pcm16le.byteLength / 2))
    if (input.length === 0) return

    this.pendingSamples.push(input)
    this.pendingCount += input.length

    if (this.pendingCount >= this.blockSamples) {
      this.flush(false)
    } else if (!this.flushTimer) {
      this.flushTimer = setTimeout(() => {
        this.flushTimer = null
        this.flush(false)
      }, FLUSH_AFTER_MS)
    }
  }

  private flush(isEnd: boolean): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer)
      this.flushTimer = null
    }

    const merged = new Int16Array(this.pendingCount)
    let offset = 0
    for (const chunk of this.pendingSamples) {
      merged.set(chunk, offset)
      offset += chunk.length
    }
    this.pendingSamples = []
    this.pendingCount = 0

    const head = this.resampler.push(merged)
    let output = head
    if (isEnd) {
      const tail = this.resampler.flush()
      output = new Float32Array(head.length + tail.length)
      output.set(head)
      output.set(tail, head.length)
    }
    if (output.length === 0) return

    // Fades only at the edges of an utterance, never inside it.
    if (this.isFirstBlock) {
      this.isFirstBlock = false
      const fade = Math.min(FADE_SAMPLES, Math.floor(output.length / 4))
      for (let f = 0; f < fade; f++) output[f] *= f / fade
    }
    if (isEnd) {
      const fade = Math.min(FADE_SAMPLES, Math.floor(output.length / 4))
      for (let f = 0; f < fade; f++) output[output.length - 1 - f] *= f / fade
    }

    const buffer = this.context.createBuffer(1, output.length, this.context.sampleRate)
    buffer.copyToChannel(output, 0)

    const source = this.context.createBufferSource()
    source.buffer = buffer
    source.connect(this.destination)

    const now = this.context.currentTime
    if (this.nextStart < now) {
      this.nextStart = now + INITIAL_LEAD_SECONDS
    }

    source.start(this.nextStart)
    this.nextStart += output.length / this.context.sampleRate
    this.sources.add(source)
    this.last = source

    source.onended = () => {
      this.sources.delete(source)
      if (this.last === source && this.sources.size === 0) {
        this.last = null
        this.isFirstBlock = true
        const list = this.waiters
        this.waiters = []
        list.forEach((w) => w())
      }
    }
  }

  /** Runs {@code callback} once everything queued so far has finished playing. */
  whenPlayed(callback: () => void): void {
    this.flush(true)
    if (this.last === null && this.sources.size === 0) {
      callback()
    } else {
      this.waiters.push(callback)
    }
  }

  /** Immediately silences all queued audio (for barge-in or hangup). */
  clear(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer)
      this.flushTimer = null
    }
    this.pendingSamples = []
    this.pendingCount = 0

    for (const source of this.sources) {
      source.onended = null
      try {
        source.stop()
      } catch {
        // Already stopped
      }
      source.disconnect()
    }
    this.sources.clear()
    this.last = null
    this.nextStart = 0
    this.resampler.reset()
    this.isFirstBlock = true

    const list = this.waiters
    this.waiters = []
    list.forEach((w) => w())
  }

  destroy(): void {
    this.clear()
  }
}
