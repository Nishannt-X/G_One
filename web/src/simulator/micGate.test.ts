import { describe, expect, it } from 'bun:test'
import { BARGE_IN_LEVEL_DBFS, HOLD_FRAMES, MicGate, OPEN_AFTER_FRAMES, PRE_ROLL_FRAMES, frameDbfs } from './micGate'

const SAMPLES = 160

/** A 20 ms frame of a 300 Hz tone at the given RMS level, tagged in its first sample so frames can be told apart. */
function frame(dbfs: number, tag = 0): ArrayBuffer {
  const pcm = new Int16Array(SAMPLES)
  const peak = Math.pow(10, dbfs / 20) * Math.SQRT2 * 32768
  for (let i = 0; i < SAMPLES; i++) pcm[i] = Math.round(peak * Math.sin((2 * Math.PI * 300 * i) / 8000))
  pcm[0] = tag
  return pcm.buffer
}

const speech = (tag = 0) => frame(-25, tag)
const echo = (tag = 0) => frame(-55, tag)
const tagOf = (b: ArrayBuffer) => new Int16Array(b)[0]

describe('frameDbfs', () => {
  it('measures the level of a frame', () => {
    expect(frameDbfs(frame(-25))).toBeCloseTo(-25, 0)
    expect(frameDbfs(new Int16Array(SAMPLES).buffer)).toBe(-Infinity)
  })
})

describe('MicGate', () => {
  it('sends every frame unchanged while nothing plays, quiet or loud', () => {
    const gate = new MicGate()
    for (const f of [echo(), speech(), new Int16Array(SAMPLES).buffer]) {
      expect(gate.push(f, false)).toEqual({ send: [f], opened: false })
    }
  })

  // The bug: the old gate dropped every frame while G-One played, so the backend never heard a barge-in.
  it('sends speech over playback, with the frames just before it, so the backend can interrupt G-One', () => {
    const gate = new MicGate()
    const sent: ArrayBuffer[] = []
    let openedAt = -1
    for (let i = 1; i <= 30; i++) {
      const r = gate.push(speech(i), true)
      sent.push(...r.send)
      if (r.opened) openedAt = i
    }
    expect(openedAt).toBe(OPEN_AFTER_FRAMES)
    expect(sent.map(tagOf)).toEqual(Array.from({ length: 30 }, (_, i) => i + 1))
  })

  it('keeps leftover speaker echo out while G-One plays', () => {
    const gate = new MicGate()
    for (let i = 0; i < 200; i++) {
      expect(gate.push(echo(), true).send).toEqual([])
    }
    expect(frameDbfs(echo())).toBeLessThan(BARGE_IN_LEVEL_DBFS)
  })

  it('ignores a click shorter than the opening time', () => {
    const gate = new MicGate()
    const sent: ArrayBuffer[] = []
    for (let round = 0; round < 20; round++) {
      for (let i = 0; i < OPEN_AFTER_FRAMES - 1; i++) sent.push(...gate.push(speech(), true).send)
      for (let i = 0; i < OPEN_AFTER_FRAMES; i++) sent.push(...gate.push(echo(), true).send)
    }
    expect(sent).toEqual([])
  })

  it('stays open through pauses inside a sentence, then closes on a long silence', () => {
    const gate = new MicGate()
    for (let i = 0; i < OPEN_AFTER_FRAMES; i++) gate.push(speech(), true)
    for (let i = 0; i < HOLD_FRAMES - 1; i++) expect(gate.push(echo(), true).send).toHaveLength(1)
    expect(gate.push(speech(), true).send).toHaveLength(1) // a word after a pause still goes up
    for (let i = 0; i < HOLD_FRAMES; i++) expect(gate.push(echo(), true).send).toHaveLength(1)
    expect(gate.push(echo(), true).send).toEqual([])
  })

  it('holds back no more than the pre-roll', () => {
    const gate = new MicGate()
    for (let i = 0; i < 50; i++) gate.push(echo(100 + i), true)
    const sent: ArrayBuffer[] = []
    for (let i = 1; i <= OPEN_AFTER_FRAMES; i++) sent.push(...gate.push(speech(i), true).send)
    expect(sent).toHaveLength(PRE_ROLL_FRAMES)
    expect(sent.slice(-OPEN_AFTER_FRAMES).map(tagOf)).toEqual([1, 2, 3, 4])
  })

  it('opens fully once playback stops (after the server clear), dropping nothing held from before', () => {
    const gate = new MicGate()
    gate.push(speech(1), true)
    gate.push(speech(2), true)
    const after = speech(3)
    expect(gate.push(after, false).send).toEqual([after])
    // Playback starts again: the earlier half-word is not replayed.
    for (let i = 4; i < 4 + OPEN_AFTER_FRAMES - 1; i++) gate.push(speech(i), true)
    const r = gate.push(speech(99), true)
    expect(r.opened).toBe(true)
    expect(r.send.map(tagOf)).toEqual([4, 5, 6, 99])
  })
})
