import { describe, expect, it } from 'bun:test'
import {
  feedReducer,
  initialFeed,
  normalizeEvent,
  pickCall,
  type FeedState,
} from './callState'
import type { ConsoleEvent } from '../types/console'

describe('feedReducer', () => {
  it('handles call lifecycle and tracks deal updates', () => {
    let state: FeedState = initialFeed

    const startEvent: ConsoleEvent = {
      type: 'call_started',
      callId: 'call-101',
      atMs: 1000,
      language: 'hi-IN',
    }
    state = feedReducer(state, { type: 'event', event: startEvent })

    expect(state.order).toContain('call-101')
    expect(state.calls['call-101'].startedMs).toBe(1000)
    expect(state.calls['call-101'].language).toBe('hi-IN')

    const joinFarmer: ConsoleEvent = {
      type: 'leg_joined',
      callId: 'call-101',
      legId: 'leg-farmer',
      role: 'farmer',
      language: 'hi-IN',
      source: 'simulator',
      atMs: 1200,
    }
    state = feedReducer(state, { type: 'event', event: joinFarmer })
    expect(state.calls['call-101'].legs.length).toBe(1)
    expect(state.calls['call-101'].legs[0].role).toBe('farmer')

    const dealEvent: ConsoleEvent = {
      type: 'deal_updated',
      callId: 'call-101',
      atMs: 2500,
      commodity: 'Red Onion',
      variety: 'Nashik Red',
      quantity: '20 quintals',
      price: 4300,
      unit: 'quintal',
      market: 'Farm gate',
      pickup: 'Monday morning; payment on delivery',
      state: 'readback',
      farmerYes: true,
      buyerYes: false,
    }
    state = feedReducer(state, { type: 'event', event: dealEvent })
    const deal = state.calls['call-101'].deal
    expect(deal).toBeDefined()
    expect(deal?.price).toBe(4300)
    expect(deal?.state).toBe('readback')
    expect(deal?.farmerYes).toBe(true)

    const endEvent: ConsoleEvent = {
      type: 'call_ended',
      callId: 'call-101',
      atMs: 5000,
      durationMs: 4000,
      reason: 'deal_agreed',
    }
    state = feedReducer(state, { type: 'event', event: endEvent })
    expect(state.calls['call-101'].ended).toBeDefined()
    expect(state.calls['call-101'].ended?.reason).toBe('deal_agreed')

    const active = pickCall(state, null)
    expect(active?.callId).toBe('call-101')
  })

  it('resets feed on reconnect while preserving finished and mock calls', () => {
    let state: FeedState = initialFeed
    state = feedReducer(state, {
      type: 'event',
      event: { type: 'call_started', callId: 'call-live', atMs: 1000, language: 'hi-IN' },
    })
    state = feedReducer(state, {
      type: 'event',
      event: { type: 'call_started', callId: 'call-done', atMs: 2000, language: 'hi-IN' },
    })
    state = feedReducer(state, {
      type: 'event',
      event: { type: 'call_ended', callId: 'call-done', atMs: 3000, durationMs: 1000, reason: 'deal_agreed' },
    })

    expect(state.order).toEqual(['call-live', 'call-done'])

    // On reset, the running call 'call-live' is cleared so server can re-send it from start
    state = feedReducer(state, { type: 'reset' })
    expect(state.order).toEqual(['call-done'])
  })
})

describe('normalizeEvent', () => {
  it('filters out invalid events and normalizes fields', () => {
    expect(normalizeEvent(null)).toBeNull()
    expect(normalizeEvent({ type: 'unknown_event' })).toBeNull()

    const valid = normalizeEvent({
      type: 'turn_heard',
      callId: 'call-abc',
      legId: 'leg-1',
      role: 'farmer',
      text: 'नमस्ते',
      atMs: 500,
      language: 'hi-IN',
    })
    expect(valid).not.toBeNull()
    expect(valid?.type).toBe('turn_heard')
  })
})
