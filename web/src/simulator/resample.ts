/**
 * Converts a stream of mono PCM16 chunks to float samples at another rate with Catmull-Rom interpolation. The output
 * does not depend on how the input is cut into chunks: each sample's right-hand neighbours are waited for rather than
 * guessed, so a chunk boundary never leaves a mark. When the rates match, samples pass through untouched.
 */
export class PcmStreamResampler {
  private readonly inRate: number
  private readonly outRate: number
  /** Unconsumed input, always starting with the sample just left of the read position. */
  private held: number[] = [0]
  /** Stream index of held[0]. */
  private base = -1
  private produced = 0

  constructor(inRate: number, outRate: number) {
    this.inRate = inRate
    this.outRate = outRate
  }

  get passthrough(): boolean {
    return this.inRate === this.outRate
  }

  /** Returns every output sample that the input so far determines. */
  push(chunk: Int16Array): Float32Array<ArrayBuffer> {
    if (this.passthrough) return toFloat(chunk)
    for (let i = 0; i < chunk.length; i++) this.held.push(chunk[i])
    return this.drain(this.base + this.held.length - 3)
  }

  /** Ends the stream, returning the held-back tail, and readies this resampler for a new one. */
  flush(): Float32Array<ArrayBuffer> {
    if (this.passthrough) return new Float32Array(0)
    const known = this.base + this.held.length - 1
    const last = this.held[this.held.length - 1]
    this.held.push(last, last)
    const tail = this.drain(known)
    this.reset()
    return tail
  }

  reset(): void {
    this.held = [0]
    this.base = -1
    this.produced = 0
  }

  /** Emits every output sample whose position is at most {@code lastIndex} in the input stream. */
  private drain(lastIndex: number): Float32Array<ArrayBuffer> {
    const out: number[] = []
    for (;;) {
      const position = (this.produced * this.inRate) / this.outRate
      const index = Math.floor(position)
      if (index > lastIndex) break
      const t = position - index
      const at = index - this.base
      const p0 = this.held[at - 1]
      const p1 = this.held[at]
      const p2 = this.held[at + 1]
      const p3 = this.held[at + 2]
      const v =
        (0.5 *
          (2 * p1 +
            (p2 - p0) * t +
            (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t +
            (3 * p1 - p0 - 3 * p2 + p3) * t * t * t)) /
        32768
      out.push(Math.max(-1, Math.min(1, v)))
      this.produced++
    }
    const next = Math.floor((this.produced * this.inRate) / this.outRate)
    const keepFrom = Math.max(0, Math.min(this.held.length - 1, next - 1 - this.base))
    if (keepFrom > 0) {
      this.held = this.held.slice(keepFrom)
      this.base += keepFrom
    }
    return Float32Array.from(out)
  }
}

function toFloat(chunk: Int16Array): Float32Array<ArrayBuffer> {
  const out = new Float32Array(chunk.length)
  for (let i = 0; i < chunk.length; i++) out[i] = chunk[i] / 32768
  return out
}
