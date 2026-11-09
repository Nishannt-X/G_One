import { MANIFEST_BASE, type Manifest } from './manifest'
import { buildPlan, cursorAt, nextBeatStart, NOT_STARTED, type Cursor, type Plan } from './timeline'

export type LoadStatus = 'loading' | 'ready' | 'failed'
export type Speed = 0.9 | 1 | 1.15

export interface VoicedState {
  status: LoadStatus
  loaded: number
  total: number
  manifest: Manifest | null
  plan: Plan | null
  /** The sample has been started and is what the page shows. */
  active: boolean
  playing: boolean
  finished: boolean
  /** Autoplay was asked for but the browser needs a tap first. */
  needsTap: boolean
  speed: Speed
  muted: boolean
  cursor: Cursor
}

const LEAD_S = 0.06
const TICK_MS = 40
const FETCHES_AT_ONCE = 6
const AUTOSTART_WAIT_MS = 300
/** The notification sounds this long after the beat starts, as the phone's banner slides in. */
const CHIME_AFTER_S = 0.3
const CHIME_PAN = { farmer: -0.75, buyer: 0.75 }

interface Graph {
  context: AudioContext
  master: GainNode
}

/** Plays the sample's clips on one Web Audio clock, so timing does not depend on React or on timers. */
export class VoicedEngine {
  private state: VoicedState = { status: 'loading', loaded: 0, total: 0, manifest: null, plan: null, active: false, playing: false, finished: false, needsTap: false, speed: 1, muted: false, cursor: NOT_STARTED }
  private listeners = new Set<() => void>()
  private progressListeners = new Set<(fraction: number, seconds: number) => void>()
  private buffers = new Map<string, AudioBuffer>()
  private graph: Graph | null = null
  private sources: AudioBufferSourceNode[] = []
  private anchorClock = 0
  private anchorTime = 0
  private timer: ReturnType<typeof setInterval> | null = null
  private loadRun = 0
  private chime: AudioBuffer | null = null

  getState = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  onProgress(listener: (fraction: number, seconds: number) => void) {
    this.progressListeners.add(listener)
    return () => { this.progressListeners.delete(listener) }
  }

  private set(patch: Partial<VoicedState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l())
  }

  async load() {
    if (this.state.status === 'ready') return
    const run = ++this.loadRun
    try {
      const response = await fetch(`${MANIFEST_BASE}manifest.json`)
      if (!response.ok) throw new Error(`manifest ${response.status}`)
      const manifest = (await response.json()) as Manifest
      const clips = manifest.beats.flatMap((b) => b.clips)
      if (run !== this.loadRun) return
      this.set({ total: clips.length, loaded: 0, manifest, status: 'loading' })
      // Decoding needs no sound, so an offline context avoids asking the browser for audio before a tap.
      const decoder = new OfflineAudioContext(1, 1, manifest.sampleRate)
      const queue = [...clips]
      let loaded = 0
      const work = async () => {
        for (let clip = queue.shift(); clip; clip = queue.shift()) {
          const audio = await fetch(`${MANIFEST_BASE}${clip.file}`)
          if (!audio.ok) throw new Error(`${clip.file} ${audio.status}`)
          const buffer = await decoder.decodeAudioData(await audio.arrayBuffer())
          if (run !== this.loadRun) return
          this.buffers.set(clip.id, buffer)
          loaded += 1
          this.set({ loaded })
        }
      }
      await Promise.all(Array.from({ length: FETCHES_AT_ONCE }, work))
      if (run !== this.loadRun) return
      this.set({ status: 'ready', plan: buildPlan(manifest) })
    } catch {
      if (run === this.loadRun) this.set({ status: 'failed' })
    }
  }

  private context(): Graph {
    if (this.graph) return this.graph
    const context = new AudioContext({ latencyHint: 'playback' })
    const master = context.createGain()
    master.gain.value = this.state.muted ? 0 : 1
    master.connect(context.destination)
    this.graph = { context, master }
    return this.graph
  }

  /** For ?autoplay=1. Starts if the browser allows sound now, otherwise asks for one tap. */
  async autostart() {
    if (this.state.status !== 'ready' || this.state.active) return
    const { context } = this.context()
    // A blocked context never resolves resume(), and an allowed one starts only after the constructor returns.
    await Promise.race([context.resume(), new Promise((done) => setTimeout(done, AUTOSTART_WAIT_MS))])
    if (this.state.active || this.graph?.context !== context) return
    if ((context.state as string) === 'running') this.start()
    else this.set({ needsTap: true, active: true })
  }

  /** Call from a click or tap, so the browser allows sound. */
  start() {
    if (this.state.status !== 'ready') return
    this.set({ active: true, needsTap: false, finished: false })
    this.playFrom(0)
  }

  private now(): number {
    if (!this.graph) return 0
    return this.anchorTime + Math.max(0, this.graph.context.currentTime - this.anchorClock) * this.state.speed
  }

  private stopSources() {
    for (const source of this.sources) {
      source.onended = null
      try { source.stop() } catch { /* a source that already ended */ }
      source.disconnect()
    }
    this.sources = []
  }

  /** Two soft tones, made here so the sample needs no extra file. */
  private chimeBuffer(context: AudioContext): AudioBuffer {
    if (this.chime?.sampleRate === context.sampleRate) return this.chime
    const rate = context.sampleRate
    const buffer = context.createBuffer(1, Math.round(rate * 0.42), rate)
    const data = buffer.getChannelData(0)
    for (const [startS, hz] of [[0, 880], [0.13, 1318.5]] as const) {
      const start = Math.round(startS * rate)
      for (let i = start; i < data.length; i++) {
        const age = (i - start) / rate
        data[i] += 0.32 * Math.min(1, age / 0.004) * Math.exp(-age * 11) * Math.sin(2 * Math.PI * hz * age)
      }
    }
    this.chime = buffer
    return buffer
  }

  private scheduleChime(context: AudioContext, master: GainNode, at: number, t: number, party: 'farmer' | 'buyer') {
    const source = context.createBufferSource()
    source.buffer = this.chimeBuffer(context)
    const pan = context.createStereoPanner?.()
    if (pan) {
      pan.pan.value = CHIME_PAN[party]
      source.connect(pan)
      pan.connect(master)
    } else source.connect(master)
    source.start(this.anchorClock + (at - t) / this.state.speed)
    this.sources.push(source)
  }

  private playFrom(t: number) {
    const { plan, speed, manifest } = this.state
    if (!plan) return
    const { context, master } = this.context()
    this.stopSources()
    this.anchorClock = context.currentTime + LEAD_S
    this.anchorTime = t
    for (const segment of plan.segments) {
      if (segment.end <= t) continue
      const buffer = this.buffers.get(segment.source.id)
      if (!buffer) continue
      const source = context.createBufferSource()
      source.buffer = buffer
      source.playbackRate.value = speed
      let tail: AudioNode = source
      if (context.createStereoPanner) {
        const panner = context.createStereoPanner()
        panner.pan.value = segment.source.pan
        source.connect(panner)
        tail = panner
      }
      tail.connect(master)
      const offset = Math.max(0, t - segment.start)
      source.start(this.anchorClock + Math.max(0, segment.start - t) / speed, offset)
      this.sources.push(source)
      const party = segment.clip === 0 ? manifest?.beats[segment.beat].deliver : null
      const chimeAt = segment.start + CHIME_AFTER_S
      if (party && chimeAt >= t) this.scheduleChime(context, master, chimeAt, t, party)
    }
    void context.resume()
    this.set({ playing: true, finished: false, cursor: cursorAt(plan, t) })
    this.startTimer()
  }

  private startTimer() {
    if (this.timer === null) this.timer = setInterval(this.tick, TICK_MS)
  }

  private stopTimer() {
    if (this.timer !== null) clearInterval(this.timer)
    this.timer = null
  }

  private tick = () => {
    const { plan, cursor } = this.state
    if (!plan) return
    const t = this.now()
    this.progressListeners.forEach((l) => l(Math.min(1, t / plan.total), Math.min(t, plan.total)))
    if (t >= plan.total) {
      this.stopTimer()
      this.set({ playing: false, finished: true, cursor: { ...cursorAt(plan, plan.total), voicing: false } })
      return
    }
    const next = cursorAt(plan, t)
    if (next.beat !== cursor.beat || next.clip !== cursor.clip || next.voicing !== cursor.voicing) this.set({ cursor: next })
  }

  toggle() {
    const { playing, finished, status, needsTap } = this.state
    if (status !== 'ready') return
    if (needsTap || finished || !this.graph) return this.start()
    if (playing) {
      void this.graph.context.suspend()
      this.stopTimer()
      this.set({ playing: false })
    } else {
      void this.graph.context.resume()
      this.set({ playing: true })
      this.startTimer()
    }
  }

  restart() {
    if (this.state.status === 'ready') this.start()
  }

  skip() {
    const { plan, playing, status } = this.state
    if (status !== 'ready' || !plan || !this.graph) return
    this.seek(nextBeatStart(plan, this.now()), playing)
  }

  /** Jumps to the start of the beat nearest to `fraction` of the way through. */
  seekToFraction(fraction: number) {
    const { plan, playing, status } = this.state
    if (status !== 'ready' || !plan) return
    const t = fraction * plan.total
    const start = plan.beatStarts.reduce((best, s) => (Math.abs(s - t) < Math.abs(best - t) ? s : best), 0)
    this.seek(start, playing || !this.graph)
  }

  private seek(t: number, keepPlaying: boolean) {
    const { plan } = this.state
    if (!plan) return
    if (t >= plan.total) {
      this.stopSources()
      this.anchorTime = plan.total
      if (this.graph) this.anchorClock = this.graph.context.currentTime
      this.tick()
      return
    }
    this.set({ active: true, needsTap: false })
    this.playFrom(t)
    this.progressListeners.forEach((l) => l(t / plan.total, t))
    if (!keepPlaying && this.graph) {
      void this.graph.context.suspend()
      this.stopTimer()
      this.set({ playing: false })
    }
  }

  setSpeed(speed: Speed) {
    if (speed === this.state.speed) return
    const running = this.state.playing
    const t = this.now()
    this.set({ speed })
    if (this.state.active && this.graph && !this.state.finished && !this.state.needsTap) {
      this.playFrom(t)
      if (!running) {
        void this.graph.context.suspend()
        this.stopTimer()
        this.set({ playing: false })
      }
    }
  }

  /** Plays from the first phone message to the end, for "replay this scene". */
  replayPhones() {
    const { plan, manifest, status } = this.state
    if (status !== 'ready' || !plan || !manifest) return
    const beat = manifest.beats.findIndex((b) => b.deliver)
    if (beat >= 0) this.seek(plan.beatStarts[beat], true)
  }

  setMuted(muted: boolean) {
    this.set({ muted })
    if (this.graph) this.graph.master.gain.value = muted ? 0 : 1
  }

  /** Stops the sample and returns the page to its usual view. */
  close() {
    this.stopSources()
    this.stopTimer()
    if (this.graph) void this.graph.context.suspend()
    this.set({ active: false, playing: false, finished: false, needsTap: false, cursor: NOT_STARTED })
    this.progressListeners.forEach((l) => l(0, 0))
  }

  dispose() {
    this.loadRun += 1
    this.stopSources()
    this.stopTimer()
    if (this.graph) void this.graph.context.close()
    this.graph = null
    if (this.state.status !== 'ready') this.set({ status: 'loading', loaded: 0 })
    this.set({ active: false, playing: false, finished: false, needsTap: false, cursor: NOT_STARTED })
  }
}
