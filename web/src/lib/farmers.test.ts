import { describe, expect, test } from 'bun:test'
import { maskedKey, mergeSeen, searchFarmers, toFarmer, type SeenCaller } from './farmers'
import type { MemoryFact } from './memory'

const fact = (field: string, value: string): MemoryFact => ({ field, value, source: 'said', callId: 'c', atMs: 1 })

describe('farmers', () => {
  test('reads name, place and crops from the facts', () => {
    const farmer = toFarmer('a'.repeat(64), 'memory', 'hi-IN', 5, [fact('name', 'Asha'), fact('district', 'Nashik'), fact('crops', 'onion, tomato')], [])
    expect(farmer.name).toBe('Asha')
    expect(farmer.district).toBe('Nashik')
    expect(farmer.crops).toEqual(['onion', 'tomato'])
    expect(farmer.lastCallMs).toBe(5)
  })

  test('a fresh sighting replaces the old entry and goes first', () => {
    const seen = (callerKey: string, seenMs: number): SeenCaller => ({ callerKey, language: 'hi-IN', seenMs, origin: 'memory' })
    const merged = mergeSeen([seen('a', 1), seen('b', 2)], seen('b', 9))
    expect(merged.map((c) => [c.callerKey, c.seenMs])).toEqual([['b', 9], ['a', 1]])
  })

  test('search matches crops and the masked key, ignoring case', () => {
    const key = 'f'.repeat(60) + 'ab12'
    const farmers = [toFarmer(key, 'memory', 'hi-IN', 1, [fact('crops', 'Onion')], [])]
    expect(searchFarmers(farmers, 'onion')).toHaveLength(1)
    expect(searchFarmers(farmers, 'AB12')).toHaveLength(1)
    expect(searchFarmers(farmers, 'wheat')).toHaveLength(0)
    expect(maskedKey(key)).toBe('Caller ••••ab12')
  })
})
