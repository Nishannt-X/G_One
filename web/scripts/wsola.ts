/**
 * Pitch-preserving time-stretch for the voiced sample: WSOLA (waveform-similarity overlap-add). Speech gets faster by
 * `tempo` while each voice keeps its pitch, unlike resampling, which would raise it.
 *
 * How it works: the output is built from 20 ms Hann-windowed frames laid down every 10 ms (50% overlap). Each frame is
 * read from the input near where the tempo says it should come from, shifted by up to 12 ms to the position whose
 * waveform best matches the natural continuation of the previous frame (normalised cross-correlation), so the overlaps
 * add up in phase and no clicks or warbles appear.
 *
 * Pure TypeScript with no dependencies, so `bun` alone reproduces the committed clips.
 */

export interface StretchOptions {
  /** Frame length in seconds. */
  frame?: number
  /** How far a frame may move to find the best match, in seconds. */
  tolerance?: number
  /** Highest peak allowed in the result, as a fraction of full scale. 0.89 is about -1 dBFS. */
  ceiling?: number
  /** Pre-emphasis used only for matching, so consonants line up as well as vowels. 0 matches on the plain waveform. */
  emphasis?: number
}

/** Plays `tempo` times faster at the same pitch. `samples` is mono PCM16 at `rate` Hz. */
export function stretch(samples: Int16Array, rate: number, tempo: number, options: StretchOptions = {}): Int16Array {
  if (!(tempo > 0)) throw new Error(`Bad tempo ${tempo}`)
  const outLength = Math.round(samples.length / tempo)
  if (tempo === 1 || samples.length === 0) return samples.slice()

  const frame = 2 * Math.round(((options.frame ?? 0.02) * rate) / 2)
  const hop = frame / 2
  const tolerance = Math.round((options.tolerance ?? 0.012) * rate)
  const ceiling = options.ceiling ?? 0.89

  const input = new Float64Array(samples.length)
  for (let i = 0; i < samples.length; i++) input[i] = samples[i] / 32768
  const at = (i: number) => (i >= 0 && i < input.length ? input[i] : 0)
  const emphasis = options.emphasis ?? 0.9
  const matched = new Float64Array(input.length)
  for (let i = 0; i < input.length; i++) matched[i] = input[i] - emphasis * (i > 0 ? input[i - 1] : 0)
  const near = (i: number) => (i >= 0 && i < matched.length ? matched[i] : 0)

  const window = new Float64Array(frame)
  for (let i = 0; i < frame; i++) window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / frame)

  const output = new Float64Array(outLength + frame)
  const weight = new Float64Array(outLength + frame)
  // How alike each frame's first half is to the previous frame's second half: 1 adds like one voice, 0 like noise.
  const alike = new Float64Array(Math.ceil(outLength / hop) + 2)
  let previous = 0
  for (let k = 0; k * hop < outLength; k++) {
    const ideal = Math.round(k * hop * tempo)
    let chosen = ideal
    if (k > 0) {
      // The candidate's first half is laid over the previous frame's second half, so match it to what would have
      // followed the previous frame if time were not stretched.
      const natural = previous + hop
      let natEnergy = 0
      for (let i = 0; i < hop; i++) natEnergy += near(natural + i) ** 2
      let best = -Infinity
      let bestAlike = 0
      for (let delta = -tolerance; delta <= tolerance; delta++) {
        const start = ideal + delta
        let dot = 0
        let energy = 0
        for (let i = 0; i < hop; i++) {
          const a = near(start + i)
          dot += a * near(natural + i)
          energy += a * a
        }
        const score = energy > 1e-12 ? dot / Math.sqrt(energy) : 0
        if (score > best) {
          best = score
          chosen = start
          bestAlike = energy > 1e-12 && natEnergy > 1e-12 ? dot / Math.sqrt(energy * natEnergy) : 1
        }
      }
      alike[k] = Math.max(0, Math.min(1, bestAlike))
    }
    const offset = k * hop
    for (let i = 0; i < frame; i++) {
      output[offset + i] += at(chosen + i) * window[i]
      weight[offset + i] += window[i]
    }
    previous = chosen
  }
  // Two Hann halves sum to one, which keeps a matched voice at its level but would drop unrelated sounds (breath,
  // fricatives) by up to 3 dB mid-overlap. Scale each overlap by the power its two halves actually add up to.
  for (let k = 1; k * hop < outLength; k++) {
    for (let i = 0; i < hop; i++) {
      const a = window[i + hop]
      const b = window[i]
      const power = a * a + b * b + 2 * a * b * alike[k]
      output[k * hop + i] /= Math.sqrt(Math.max(power, 1e-6))
    }
  }
  for (let i = 0; i < hop && i < outLength; i++) if (weight[i] > 1e-6) output[i] /= Math.max(weight[i], 0.5)

  let peak = 0
  for (let i = 0; i < outLength; i++) peak = Math.max(peak, Math.abs(output[i]))
  const gain = peak > ceiling ? ceiling / peak : 1
  const result = new Int16Array(outLength)
  for (let i = 0; i < outLength; i++) result[i] = Math.max(-32767, Math.min(32767, Math.round(output[i] * gain * 32768)))
  return result
}
