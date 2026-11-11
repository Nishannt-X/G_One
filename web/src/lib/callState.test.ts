import { describe, expect, it } from 'bun:test'
import {
  feedReducer,
  followedCall,
  initialFeed,
  normalizeEvent,
  pickCall,
  pinCall,
  type FeedState,
} from './callState'
import type { ConsoleEvent } from '../types/console'

describe('language changes', () => {
  it('updates the leg and leaves a note on the conversation', () => {
    let state: FeedState = initialFeed
    state = feedReducer(state, { type: 'event', event: { type: 'call_started', callId: 'c', atMs: 1, language: 'auto' } })
    state = feedReducer(state, { type: 'event', event: { type: 'leg_joined', callId: 'c', atMs: 2, legId: 'f', role: 'farmer', source: 'simulator', language: 'auto' } })
    state = feedReducer(state, { type: 'event', event: { type: 'leg_joined', callId: 'c', atMs: 3, legId: 'b', role: 'buyer', source: 'simulator', language: 'en-IN' } })
    state = feedReducer(state, { type: 'event', event: { type: 'language_changed', callId: 'c', atMs: 4, legId: 'f', role: 'farmer', from: 'auto', to: 'hi-IN', reason: 'named' } })

    const call = state.calls.c
    expect(call.language).toBe('hi-IN')
    expect(call.legs.find((l) => l.legId === 'f')?.language).toBe('hi-IN')
    expect(call.legs.find((l) => l.legId === 'b')?.language).toBe('en-IN')
    expect(call.languageNotes).toHaveLength(1)
    expect(call.languageNotes[0]).toMatchObject({ role: 'farmer', from: 'auto', to: 'hi-IN' })
  })

  it('keeps a valid language change from the feed and drops a broken one', () => {
    const event = { type: 'language_changed', callId: 'c', atMs: 4, legId: 'f', role: 'farmer', from: 'auto', to: 'hi-IN', reason: 'named' }
    expect(normalizeEvent(event)?.type).toBe('language_changed')
    expect(normalizeEvent({ ...event, to: 3 })).toBeNull()
  })
})

describe('a private question to G-One', () => {
  it('shows the question and the answer on the asker side, both marked private, with no timing', () => {
    let state: FeedState = initialFeed
    state = feedReducer(state, { type: 'event', event: { type: 'call_started', callId: 'c', atMs: 1, language: 'hi-IN' } })
    state = feedReducer(state, { type: 'event', event: { type: 'aside_answered', callId: 'c', atMs: 9, legId: 'b', role: 'buyer', language: 'en-IN', question: 'G-One, how much?', answer: 'No quantity has been said on this call yet.', startedMs: 5 } })

    const call = state.calls.c
    expect(call.items.map((i) => [i.kind, 'aside' in i ? i.aside : undefined])).toEqual([['turn', true], ['assistant', true]])
    expect(call.items[0]).toMatchObject({ role: 'buyer', text: 'G-One, how much?', startedMs: 5 })
    expect(call.timings).toHaveLength(0)
  })

  it('drops a broken private event from the feed', () => {
    const event = { type: 'aside_answered', callId: 'c', atMs: 9, legId: 'b', role: 'buyer', language: 'en-IN', question: 'q', answer: 'a', startedMs: 5 }
    expect(normalizeEvent(event)?.type).toBe('aside_answered')
    expect(normalizeEvent({ ...event, answer: 3 })).toBeNull()
  })
})

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

describe('pickCall', () => {
  const start = (state: FeedState, callId: string, atMs: number) => feedReducer(state, { type: 'event', event: { type: 'call_started', callId, atMs, language: 'hi-IN' } })
  const end = (state: FeedState, callId: string, atMs: number) => feedReducer(state, { type: 'event', event: { type: 'call_ended', callId, atMs, durationMs: 1, reason: 'hangup' } })

  it('keeps following the newest running call after a reconnect resends an older one', () => {
    let state = start(initialFeed, 'older', 1000)
    state = start(state, 'newer', 2000)
    expect(pickCall(state, null)?.callId).toBe('newer')
    // The console feed reconnects: running calls are dropped, then the server resends them, the older one first or last.
    state = feedReducer(state, { type: 'reset' })
    state = start(state, 'newer', 2000)
    state = start(state, 'older', 1000)
    expect(state.order).toEqual(['newer', 'older'])
    expect(pickCall(state, null)?.callId).toBe('newer')
  })

  it('prefers a running call to a newer finished one, and the newest call when all have ended', () => {
    let state = start(initialFeed, 'running', 1000)
    state = start(state, 'done', 2000)
    state = end(state, 'done', 2500)
    expect(pickCall(state, null)?.callId).toBe('running')
    state = end(state, 'running', 3000)
    expect(pickCall(state, null)?.callId).toBe('done')
  })

  it('follows a pinned call over everything else', () => {
    let state = start(initialFeed, 'a', 1000)
    state = start(state, 'b', 2000)
    expect(pickCall(state, 'a')?.callId).toBe('a')
    expect(pickCall(state, 'missing')?.callId).toBe('b')
  })

  it('drops a hand-picked call once a newer call starts, so the Live tab shows the new call without a reload', () => {
    let state = start(initialFeed, 'a', 1000)
    state = end(start(state, 'b', 2000), 'b', 2500)
    // Pinning the newest call, or an older one, keeps it while nothing newer starts.
    const pin = pinCall(state, 'a')
    expect(followedCall(state, pin)?.callId).toBe('a')
    expect(followedCall(state, pinCall(state, 'b'))?.callId).toBe('b')
    state = start(state, 'c', 3000)
    expect(followedCall(state, pin)?.callId).toBe('c')
    expect(followedCall(state, null)?.callId).toBe('c')
  })
})
