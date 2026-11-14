/**
 * Playing a demo deal step by step: the steps (the call, then each road leg and each stop), a playhead over them, and
 * what the map and the detail list show at any moment. Pure functions, so the player can be tested without a browser.
 */
import type { Deal, DealStatus } from './deals'

export interface Position {
  /** Index of the current step. Equal to the step count once the whole deal has played. */
  index: number
  /** How far through the current step, 0 to 1. */
  fraction: number
}

export function totalSeconds(steps: readonly { seconds: number }[]): number {
  return steps.reduce((sum, s) => sum + s.seconds, 0)
}

/** Where the playhead at `t` seconds sits among the steps. Past the end, every step is done. */
export function locate(steps: readonly { seconds: number }[], t: number): Position {
  let start = 0
  for (let index = 0; index < steps.length; index++) {
    const end = start + steps[index].seconds
    if (t < end) return { index, fraction: Math.max(0, (t - start) / steps[index].seconds) }
    start = end
  }
  return { index: steps.length, fraction: 1 }
}

/** The time at which step `index` starts. */
export function stepStart(steps: readonly { seconds: number }[], index: number): number {
  return totalSeconds(steps.slice(0, Math.max(0, index)))
}

/** The playhead after pressing Step: the start of the next step, or the end. */
export function nextStepTime(steps: readonly { seconds: number }[], t: number): number {
  const { index } = locate(steps, t)
  return stepStart(steps, Math.min(steps.length, index + 1))
}

/** With reduced motion each step shows its finished state, so nothing moves between steps: the map jumps. */
export function displayPosition(at: Position, reducedMotion: boolean): Position {
  return reducedMotion ? { index: at.index, fraction: 1 } : at
}

// The player.

export type Speed = 1 | 2 | 4
export const SPEEDS: readonly Speed[] = [1, 2, 4]

export interface PlayerState {
  t: number
  playing: boolean
  speed: Speed
  /** False until the first Play or Step: the deal then shows whole, like any other. */
  started: boolean
}

export type PlayerAction =
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'reset' }
  | { type: 'restart' }
  | { type: 'step' }
  | { type: 'speed'; speed: Speed }
  | { type: 'tick'; dt: number }

export const INITIAL_PLAYER: PlayerState = { t: 0, playing: false, speed: 1, started: false }

/** A background tab pauses frames; a long gap must not skip whole steps. */
const MAX_TICK = 0.25

export function reducePlayer(state: PlayerState, action: PlayerAction, steps: readonly { seconds: number }[]): PlayerState {
  const total = totalSeconds(steps)
  switch (action.type) {
    case 'play':
      return { ...state, t: state.t >= total ? 0 : state.t, playing: true, started: true }
    case 'pause':
      return { ...state, playing: false }
    case 'reset':
      return { ...INITIAL_PLAYER, speed: state.speed }
    case 'restart':
      return { ...state, t: 0, playing: true, started: true }
    case 'step':
      return { ...state, t: nextStepTime(steps, state.t >= total ? 0 : state.t), playing: false, started: true }
    case 'speed':
      return { ...state, speed: action.speed }
    case 'tick': {
      if (!state.playing) return state
      const t = Math.min(total, state.t + Math.min(MAX_TICK, Math.max(0, action.dt)) * state.speed)
      return { ...state, t, playing: t < total }
    }
  }
}

// Demo steps.

export type DemoStep =
  | { kind: 'call'; seconds: number; title: string }
  | { kind: 'travel'; stop: number; seconds: number; title: string }
  | { kind: 'stop'; stop: number; seconds: number; title: string }

/** The call, then for each stop the drive to it and the stop itself. Longer legs take a little longer to play. */
export function demoSteps(deal: Deal): DemoStep[] {
  const steps: DemoStep[] = [{ kind: 'call', seconds: 3, title: 'Deal agreed on a call' }]
  deal.stops.forEach((stop, i) => {
    if (i > 0) steps.push({ kind: 'travel', stop: i, seconds: Math.min(4, 1.2 + stop.km / 250), title: `On the road to ${stop.short}` })
    steps.push({ kind: 'stop', stop: i, seconds: stop.difficulty ? 4.5 : 2.5, title: `${i + 1}. ${stop.short}` })
  })
  return steps
}

export interface DemoView {
  /** Stops reached so far: their entries show in the detail list and their pins are solid. */
  reached: number
  /** The stop the truck is at, highlighted; null on the road or before pickup. */
  current: number | null
  /** The truck: heading to stop `leg`, `fraction` of the way there. */
  truck: { leg: number; fraction: number } | null
  status: DealStatus
  title: string
  ended: boolean
}

/** What a demo deal shows at one moment of the playback. */
export function demoView(deal: Deal, steps: readonly DemoStep[], at: Position): DemoView {
  if (at.index >= steps.length) {
    return { reached: deal.stops.length, current: null, truck: null, status: deal.status, title: 'Played to the end', ended: true }
  }
  const step = steps[at.index]
  const statusAfter = (i: number): DealStatus => {
    for (let k = i; k >= 0; k--) {
      const s = deal.stops[k].statusAfter
      if (s) return s
    }
    return 'awaiting-pickup'
  }
  if (step.kind === 'call') return { reached: 0, current: null, truck: null, status: 'awaiting-pickup', title: step.title, ended: false }
  if (step.kind === 'travel') {
    return { reached: step.stop, current: null, truck: { leg: step.stop, fraction: at.fraction }, status: statusAfter(step.stop - 1), title: step.title, ended: false }
  }
  return { reached: step.stop + 1, current: step.stop, truck: { leg: step.stop, fraction: 1 }, status: statusAfter(step.stop), title: step.title, ended: false }
}
