import { describe, expect, test } from 'bun:test'
import { seedFarmers } from '../dev/seedFarmers'
import { toFarmer, type Farmer } from './farmers'
import type { MemoryFact } from './memory'
import { completeness, groupedProfile, PROFILE_GROUPS, PROFILE_VARIABLES, variableValue, type ProfileVariable } from './profileVariables'

// Synthetic farmers only.
const NOW = 1_794_132_000_000
const farmers = seedFarmers(NOW)
const byKey = (c: string) => farmers.find((f) => f.callerKey.startsWith(c.repeat(2))) as Farmer
const fact = (field: string, value: string, atMs = 1, source: 'said' | 'inferred' = 'said'): MemoryFact => ({ field, value, source, callId: 'c', atMs })

describe('profile variables', () => {
  test('the catalog starts with the stored fields, each in one of the four groups', () => {
    expect(PROFILE_VARIABLES.map((v) => v.key)).toEqual(['name', 'language', 'village', 'district', 'state', 'crops', 'typicalQuantity', 'usualMandi', 'paymentPreference', 'transport', 'storageAccess'])
    for (const v of PROFILE_VARIABLES) expect(PROFILE_GROUPS).toContain(v.group)
    expect(new Set(PROFILE_VARIABLES.map((v) => v.key)).size).toBe(PROFILE_VARIABLES.length)
  })

  test('completeness counts known variables against the catalog', () => {
    expect(completeness(byKey('a1'))).toEqual({ known: 11, total: 11 })
    // Name told, language inferred from the calls.
    expect(completeness(byKey('f6'))).toEqual({ known: 2, total: 11 })
    // No name; one inferred crop; the call language.
    expect(completeness(byKey('a7'))).toEqual({ known: 2, total: 11 })
  })

  test('adding a variable to the catalog raises the total, and a stored fact for it makes it known', () => {
    const extended: ProfileVariable[] = [...PROFILE_VARIABLES, { key: 'irrigation', label: 'Irrigation', group: 'Produce' }]
    const sparse = byKey('f6')
    expect(completeness(sparse, extended)).toEqual({ known: 2, total: 12 })
    const told = toFarmer('9'.repeat(64), 'seed', 'hi-IN', 5, [fact('irrigation', 'borewell', 3), fact('irrigation', 'canal', 7)], [])
    // The irrigation fact and the call language.
    expect(completeness(told, extended)).toEqual({ known: 2, total: 12 })
    expect(completeness(told).known).toBe(1)
    expect(variableValue(told, 'irrigation')).toEqual({ value: 'Canal', source: 'said', atMs: 7 })
    const produce = groupedProfile(sparse, extended).find((g) => g.group === 'Produce')
    expect(produce?.rows.map((r) => [r.variable.label, r.value?.value ?? 'Not told yet'])).toEqual([['Crops', 'Not told yet'], ['Typical quantity', 'Not told yet'], ['Irrigation', 'Not told yet']])
  })

  test('groups come in order with every variable; unknown ones have no value', () => {
    const groups = groupedProfile(byKey('f6'))
    expect(groups.map((g) => g.group)).toEqual(['Identity', 'Produce', 'Selling', 'Logistics'])
    expect(groups.flatMap((g) => g.rows).length).toBe(PROFILE_VARIABLES.length)
    const identity = groups[0].rows.map((r) => [r.variable.key, r.value?.value ?? null, r.value?.source ?? null])
    expect(identity).toEqual([['name', 'Basavaraj', 'said'], ['language', 'Kannada', 'inferred'], ['village', null, null], ['district', null, null], ['state', null, null]])
    // A group the catalog leaves empty is not shown.
    expect(groupedProfile(byKey('f6'), [{ key: 'name', label: 'Name', group: 'Identity' }]).map((g) => g.group)).toEqual(['Identity'])
  })

  test('values read in English letters with a capital, crops mark the inferred ones, dates come from the facts', () => {
    const rich = byKey('a1')
    expect(variableValue(rich, 'transport')?.value).toBe('Needs truck')
    expect(variableValue(rich, 'paymentPreference')?.value).toBe('UPI')
    expect(variableValue(rich, 'language')).toMatchObject({ value: 'Hindi', source: 'inferred' })
    expect(variableValue(rich, 'crops')).toEqual({ value: 'red onion, tomato, grapes (inferred), garlic', source: 'said', atMs: NOW - 86_400_000 })
    expect(variableValue(byKey('a7'), 'crops')?.source).toBe('inferred')
    const native = toFarmer('8'.repeat(64), 'seed', 'hi-IN', 5, [fact('district', 'नासिक', 4)], [])
    expect(variableValue(native, 'district')).toEqual({ value: 'Nasik', source: 'said', atMs: 4 })
  })
})
