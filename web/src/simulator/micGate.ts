/**
 * Decides which microphone frames go to the backend while G-One's voice is playing.
 *
 * The backend needs to hear the person over G-One to stop it (barge-in), so the microphone must stay open during
 * playback. Laptop speakers also reach the microphone, though; the browser's echo cancellation removes most of that, and
 * this gate drops what is left: while G-One plays, a frame goes up only once the microphone has been clearly louder than
 * leftover echo for a moment, and then the gate stays open for a while so the backend hears whole words. The frames
 * held back just before it opened go up first, so the start of the first word is not lost. When nothing is playing,
 * every frame goes up unchanged.
 *
 * The levels assume 20 ms frames of 8 kHz PCM16 after the browser's echo cancellation and automatic gain control.
 */

/**
 * A frame at least this loud (RMS, dBFS) counts as the person speaking over playback. Speech near a laptop microphone
 * after automatic gain control is typically -30 to -15 dBFS; echo left over after echo cancellation is usually well below
 * -45. The backend's own fallback voice detector counts -42 dBFS as voice, so this sits a little above it.
 */
export const BARGE_IN_LEVEL_DBFS = -40
/** Loud frames, net of quiet ones, needed before the gate opens: 4 x 20 ms = 80 ms. A click or a cough stays out. */
export const OPEN_AFTER_FRAMES = 4
/** Once open, the gate stays open until this many quiet frames in a row: 40 x 20 ms = 800 ms, the pauses inside a sentence. */
export const HOLD_FRAMES = 40
/** Frames held back while closed and sent when the gate opens: 10 x 20 ms = 200 ms, the start of the first word. */
export const PRE_ROLL_FRAMES = 10

export interface GateResult {
  /** The frames to send now, oldest first. */
  send: ArrayBuffer[]
  /** True when the gate has just opened during playback: the person is talking over G-One. */
  opened: boolean
}

/** The level of a PCM16 little-endian frame in dBFS; -Infinity for silence. */
export function frameDbfs(pcm16le: ArrayBuffer): number {
  const samples = new Int16Array(pcm16le, 0, Math.floor(pcm16le.byteLength / 2))
  if (samples.length === 0) return -Infinity
  let sum = 0
  for (const s of samples) sum += s * s
  const rms = Math.sqrt(sum / samples.length) / 32768
  return rms > 0 ? 20 * Math.log10(rms) : -Infinity
}

export class MicGate {
  private preRoll: ArrayBuffer[] = []
  private loudScore = 0
  private holdLeft = 0

  /**
   * @param frame one microphone frame
   * @param assistantPlaying whether G-One's audio is sounding or queued to sound right now
   */
  push(frame: ArrayBuffer, assistantPlaying: boolean): GateResult {
    if (!assistantPlaying) {
      this.reset()
      return { send: [frame], opened: false }
    }
    const loud = frameDbfs(frame) >= BARGE_IN_LEVEL_DBFS
    if (this.holdLeft > 0) {
      this.holdLeft = loud ? HOLD_FRAMES : this.holdLeft - 1
      return { send: [frame], opened: false }
    }
    this.loudScore = loud ? this.loudScore + 1 : Math.max(0, this.loudScore - 1)
    this.preRoll.push(frame)
    if (this.preRoll.length > PRE_ROLL_FRAMES) this.preRoll.shift()
    if (this.loudScore < OPEN_AFTER_FRAMES) {
      return { send: [], opened: false }
    }
    const send = this.preRoll
    this.preRoll = []
    this.loudScore = 0
    this.holdLeft = HOLD_FRAMES
    return { send, opened: true }
  }

  private reset(): void {
    this.preRoll = []
    this.loudScore = 0
    this.holdLeft = 0
  }
}
