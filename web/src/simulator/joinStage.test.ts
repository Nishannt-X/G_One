import { describe, expect, it } from 'bun:test'
import { parseRoute } from '../lib/route'
import { parseJoinCall } from '../lib/lan'
import { joinStage } from './joinStage'
import { parseServerMessage, socketUrl } from './protocol'

const call = (over: Partial<Parameters<typeof joinStage>[2]> = {}) => ({ status: 'idle' as const, micError: null, refused: false, lines: [], ...over })

describe('the join route', () => {
  it('reads the call id from the hash', () => {
    const route = parseRoute('#/join?call=call-abc-123')
    expect(route.view).toBe('join')
    expect(parseJoinCall(route.query)).toBe('call-abc-123')
  })
  it('keeps a link without a call on the join page', () => {
    const route = parseRoute('#/join')
    expect(route.view).toBe('join')
    expect(parseJoinCall(route.query)).toBeNull()
  })
})

describe('joinStage', () => {
  it('stops at a bad link before anything else', () => {
    expect(joinStage(null, false, call())).toBe('bad-link')
  })
  it('stops on an insecure page, where a phone cannot use the microphone', () => {
    expect(joinStage('c', false, call())).toBe('insecure')
  })
  it('waits for a tap, because audio needs a user gesture', () => {
    expect(joinStage('c', true, call())).toBe('ready')
  })
  it('follows the call', () => {
    expect(joinStage('c', true, call({ status: 'connecting' }))).toBe('connecting')
    expect(joinStage('c', true, call({ status: 'reconnecting' }))).toBe('reconnecting')
    expect(joinStage('c', true, call({ status: 'ended' }))).toBe('ended')
  })
  it('counts as waiting until the first line arrives', () => {
    expect(joinStage('c', true, call({ status: 'live' }))).toBe('waiting')
    expect(joinStage('c', true, call({ status: 'live', lines: [{ kind: 'g_one', language: 'en-IN', text: 'Hello' }] }))).toBe('in-call')
  })
  it('names why a join failed', () => {
    expect(joinStage('c', true, call({ status: 'error', micError: 'blocked' }))).toBe('mic-denied')
    expect(joinStage('c', true, call({ status: 'error', refused: true }))).toBe('refused')
    expect(joinStage('c', true, call({ status: 'error' }))).toBe('unreachable')
  })
})

describe('the buyer protocol', () => {
  it('reads a transcript line', () => {
    expect(parseServerMessage('{"type":"line","kind":"you_hear","language":"en-IN","text":"Hello"}')).toEqual({ type: 'line', kind: 'you_hear', language: 'en-IN', text: 'Hello' })
  })
  it('drops a line of a kind it does not know', () => {
    expect(parseServerMessage('{"type":"line","kind":"whisper","language":"hi-IN","text":"private"}')).toBeNull()
  })
  it('joins the named call over wss on an https page', () => {
    const url = socketUrl({ role: 'buyer', lang: 'en-IN', caller: null, callId: 'call-1' }, { protocol: 'https:', host: '172.20.10.4:5173' })
    expect(url).toBe('wss://172.20.10.4:5173/ws/simulator?role=buyer&lang=en-IN&callId=call-1')
  })
})
