import { describe, expect, test } from 'bun:test'
import type { TimelineItem } from '../lib/callState'
import { notPassedOn } from './notPassedOn'

const turn = (key: string, role: 'farmer' | 'buyer'): TimelineItem => ({ kind: 'turn', key, legId: `${role}-leg`, role, language: 'en-IN', text: 'G-One, how much?', startedMs: 1, atMs: 1 })
const said = (key: string, role: 'farmer' | 'buyer'): TimelineItem => ({ kind: 'assistant', key, legId: `${role}-leg`, role, language: 'en-IN', text: 'Twenty quintals so far.', atMs: 2 })

describe('not passed on', () => {
  test('a question G-One answered to the asker is marked', () => {
    expect(notPassedOn([turn('t', 'buyer'), said('a', 'buyer')], 't', false)).toBe(true)
  })

  test('a translated turn is never marked', () => {
    expect(notPassedOn([turn('t', 'buyer'), said('a', 'buyer')], 't', true)).toBe(false)
  })

  test('a line to the other party does not count', () => {
    expect(notPassedOn([turn('t', 'buyer'), said('a', 'farmer')], 't', false)).toBe(false)
  })

  test('a turn still waiting for its translation is not marked', () => {
    expect(notPassedOn([turn('t', 'farmer')], 't', false)).toBe(false)
  })
})
