import type { LanguageCode } from './languages'
import type { Scenario, StepId } from './scenarios'

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

export type Status = 'done' | 'active' | 'upcoming'

export function statusOf(stepIndex: number, at: Position): Status {
  if (stepIndex < at.index) return 'done'
  return stepIndex === at.index ? 'active' : 'upcoming'
}

/** Has the playhead reached at least `fraction` of the step with this id? */
export function reached(scenario: Scenario, at: Position, id: StepId, fraction = 0): boolean {
  const index = scenario.steps.findIndex((s) => s.id === id)
  return at.index > index || (at.index === index && at.fraction >= fraction)
}

/** 0 to 1 progress through one step: 0 before it, 1 after it. */
export function progressOf(scenario: Scenario, at: Position, id: StepId): number {
  const index = scenario.steps.findIndex((s) => s.id === id)
  if (at.index < index) return 0
  return at.index > index ? 1 : at.fraction
}

/** The language each half of the call thread carries at this moment. */
export function threadLanguages(scenario: Scenario, at: Position): { farmer: LanguageCode; buyer: LanguageCode } {
  const switched = reached(scenario, at, 'switch', 0.4)
  const { side, to } = scenario.languageSwitch
  return {
    farmer: switched && side === 'farmer' ? to : scenario.farmer.language,
    buyer: switched && side === 'buyer' ? to : scenario.buyer.language,
  }
}

/** The farmer's private halo: only during the price-reference and caution beats. */
export function whisperOn(scenario: Scenario, at: Position): boolean {
  const step = scenario.steps[at.index]
  return step?.id === 'price-ref' || step?.id === 'caution'
}

/** The scenario clock: steady within a step, ticking towards the next step's time while the truck is on the road. */
export function clockAt(scenario: Scenario, at: Position): number {
  const steps = scenario.steps
  if (at.index >= steps.length) return steps[steps.length - 1].atMin
  const step = steps[at.index]
  if (step.id !== 'transit') return step.atMin
  const next = steps[at.index + 1]?.atMin ?? step.atMin
  return Math.round(step.atMin + (next - step.atMin) * at.fraction)
}

export function formatClock(minutes: number): string {
  const day = Math.floor(minutes / 1440) + 1
  const inDay = minutes % 1440
  const hh = String(Math.floor(inDay / 60)).padStart(2, '0')
  const mm = String(inDay % 60).padStart(2, '0')
  return `Day ${day}, ${hh}:${mm}`
}
