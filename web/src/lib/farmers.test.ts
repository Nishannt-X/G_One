import { describe, expect, test } from 'bun:test'
import { cropsOf, maskedKey, mergeSeen, openFollowUps, searchFarmers, toFarmer, type SeenCaller } from './farmers'
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

  test('reads the stored crop:<name> facts, as the seed and the summarizer write them', () => {
    const facts = [fact('name', 'Suresh'), fact('crop:tomato', 'tomato'), fact('district', 'Nashik'), fact('crop:red onion', 'red onion')]
    expect(toFarmer('a'.repeat(64), 'memory', 'hi-IN', 5, facts, []).crops).toEqual(['tomato', 'red onion'])
    expect(cropsOf([fact('crop:wheat', ''), fact('crops', 'Wheat, rice')])).toEqual(['wheat', 'rice'])
  })

  test('open follow-ups match the assistant memory across older gists, capped at five', () => {
    const gist = (startedMs: number, followUps: string[]) => ({ callId: String(startedMs), startedMs, endedMs: startedMs, summary: 's', followUps })
    // The backend has already removed DONE items from the gist that held them, so what is left is open.
    expect(openFollowUps([gist(2, ['sell tomato', 'Check rain']), gist(4, ['find onion buyer', 'check  rain']), gist(3, [])])).toEqual(['find onion buyer', 'check rain', 'sell tomato', 'Check rain'])
    expect(openFollowUps([gist(1, ['still open']), gist(2, []), gist(3, []), gist(4, ['new'])])).toEqual(['new', 'still open'])
    expect(openFollowUps([gist(1, ['sixth']), gist(2, ['fifth']), gist(3, ['fourth']), gist(4, ['third']), gist(5, ['second']), gist(6, ['first'])])).toEqual(['first', 'second', 'third', 'fourth', 'fifth'])
    expect(openFollowUps([gist(1, ['sell tomato']), gist(2, [])])).toEqual(['sell tomato'])
    expect(openFollowUps([gist(1, ['x'.repeat(161)])])).toEqual(['x'.repeat(160) + '…'])
    expect(openFollowUps([])).toEqual([])
  })
})
