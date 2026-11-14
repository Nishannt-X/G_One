import { describe, expect, test } from 'bun:test'
import { seedFarmers } from '../dev/seedFarmers'
import type { Farmer } from './farmers'
import { callRows, closeTop, farmersHash, focusReturnSelector, overlayStack, parseFarmersHash, shownLocation, type FarmersLocation } from './farmersOverlay'

// Synthetic farmers only.
const NOW = 1_794_132_000_000
const farmers = seedFarmers(NOW)
const byKey = (c: string) => farmers.find((f) => f.callerKey.startsWith(c.repeat(2))) as Farmer
const rich = byKey('a1')
const bengali = byKey('b2')
const at = (key: string | null, callId: string | null = null, seed = false): FarmersLocation => ({ key, callId, seed })

describe('farmers overlays: deep links', () => {
  test('reads the farmer, the call and the seed from the hash', () => {
    expect(parseFarmersHash('#/farmers')).toEqual(at(null))
    expect(parseFarmersHash('#/farmers?seed=1')).toEqual(at(null, null, true))
    expect(parseFarmersHash(`#/farmers/${rich.callerKey}`)).toEqual(at(rich.callerKey))
    expect(parseFarmersHash(`#/farmers/${rich.callerKey}?call=seed-1`)).toEqual(at(rich.callerKey, 'seed-1'))
    expect(parseFarmersHash(`#/farmers/${rich.callerKey}?seed=1&call=seed-1`)).toEqual(at(rich.callerKey, 'seed-1', true))
    // A call needs a farmer; other pages are not the Farmers tab.
    expect(parseFarmersHash('#/farmers?call=seed-1')).toEqual(at(null))
    expect(parseFarmersHash(`#/farmers/${rich.callerKey}?call=`)).toEqual(at(rich.callerKey))
    expect(parseFarmersHash('#/analytics/calls?id=seed-1')).toEqual(at(null))
  })

  test('builds hashes that read back the same', () => {
    expect(farmersHash(at(null))).toBe('#/farmers')
    expect(farmersHash(at(null, null, true))).toBe('#/farmers?seed=1')
    expect(farmersHash(at('k1', 'call 1/x', true))).toBe('#/farmers/k1?seed=1&call=call+1%2Fx')
    for (const loc of [at(rich.callerKey), at(rich.callerKey, 'seed-1', true), at('k1', 'call 1/x')]) expect(parseFarmersHash(farmersHash(loc))).toEqual(loc)
  })
})

describe('farmers overlays: stack', () => {
  test('a farmer opens alone; their own call stacks on top', () => {
    expect(overlayStack(farmers, at(rich.callerKey)).map((l) => l.kind)).toEqual(['farmer'])
    const stack = overlayStack(farmers, at(rich.callerKey, 'seed-2'))
    expect(stack.map((l) => l.kind)).toEqual(['farmer', 'call'])
    expect(stack[1].kind === 'call' && stack[1].gist.callId).toBe('seed-2')
  })

  test("another farmer's call id, an unknown call or an unknown farmer is ignored", () => {
    expect(overlayStack(farmers, at(rich.callerKey, 'seed-3')).map((l) => l.kind)).toEqual(['farmer'])
    expect(overlayStack(farmers, at(rich.callerKey, 'nope')).map((l) => l.kind)).toEqual(['farmer'])
    expect(overlayStack(farmers, at('0'.repeat(64), 'seed-1'))).toEqual([])
    expect(overlayStack(null, at(rich.callerKey))).toEqual([])
    expect(overlayStack(farmers, at(null))).toEqual([])
  })

  test("closing with an ignored call id closes the farmer, not a call that never showed", () => {
    const foreign = at(rich.callerKey, 'seed-3', true)
    const shown = shownLocation(foreign, overlayStack(farmers, foreign))
    expect(shown).toEqual(at(rich.callerKey, null, true))
    expect(closeTop(shown)).toEqual(at(null, null, true))
    const own = at(rich.callerKey, 'seed-1')
    expect(shownLocation(own, overlayStack(farmers, own))).toEqual(own)
  })

  test('Escape closes only the top overlay: a call back to its farmer, then the farmer back to the list', () => {
    const both = at(rich.callerKey, 'seed-1', true)
    const farmerOnly = closeTop(both)
    expect(farmerOnly).toEqual(at(rich.callerKey, null, true))
    expect(overlayStack(farmers, farmerOnly).map((l) => l.kind)).toEqual(['farmer'])
    expect(closeTop(farmerOnly)).toEqual(at(null, null, true))
    expect(farmersHash(closeTop(farmerOnly))).toBe('#/farmers?seed=1')
  })

  test('focus returns to the call row, then to the farmer row', () => {
    expect(focusReturnSelector(at(rich.callerKey, 'seed-1'))).toBe('[data-call-row="seed-1"]')
    expect(focusReturnSelector(at(rich.callerKey))).toBe(`[data-farmer-row="${rich.callerKey}"]`)
    expect(focusReturnSelector(at(null))).toBeNull()
    expect(focusReturnSelector(at('k', 'a"b\\c'))).toBe('[data-call-row="a\\"b\\\\c"]')
  })
})

describe('farmers overlays: call rows', () => {
  test('one basic row per call, newest first, with length, language, outcome, deal line and follow-ups', () => {
    const rows = callRows(rich, rich.deals)
    expect(rows.map((r) => r.callId)).toEqual(['seed-1', 'seed-2', 'seed-2b'])
    expect(rows[0]).toMatchObject({ lengthMs: 360_000, language: 'hi-IN', outcome: { label: 'Deal agreed', tone: 'ok' }, dealLine: '10 quintal red onion' })
    expect(rows[0].followUps).toEqual(['Check the pickup and UPI payment with the Pune trader (due ~tomorrow)'])
    expect(rows[1]).toMatchObject({ outcome: { label: 'No deal' }, deal: null, dealLine: null, followUps: [] })
    expect(rows[2].deal?.buyer).toBe('Commission agent, Nashik')
    expect(callRows(bengali, bengali.deals)[0].language).toBe('bn-IN')
  })

  test('while deals load the outcome says so, and a call without an end has no length', () => {
    expect(callRows(rich, undefined)[0]).toMatchObject({ outcome: { label: 'Checking deal…', tone: 'plain' }, deal: null })
    const open = { ...rich, gists: [{ ...rich.gists[0], endedMs: rich.gists[0].startedMs }] }
    expect(callRows(open, [])[0].lengthMs).toBeNull()
  })
})
