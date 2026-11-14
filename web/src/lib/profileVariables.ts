import { englishText } from './farmersScript'
import type { Farmer, FarmerValue } from './farmers'
import { languageLabel } from './format'

/**
 * Everything G-One can learn about a farmer, grouped as the Farmers overlay shows it. A variable is known once the
 * farmer has told it (or G-One has inferred it) on a call. To profile farmers further, add a variable here: the overlay
 * shows it as "Not told yet" and counts it in the completeness line until a stored fact with the same field arrives.
 */
export const PROFILE_GROUPS = ['Identity', 'Produce', 'Selling', 'Logistics'] as const
export type ProfileGroup = (typeof PROFILE_GROUPS)[number]

export interface ProfileVariable {
  /** The stored fact field, as the backend writes it. `crops` collects every `crop:<name>` fact. */
  key: string
  label: string
  group: ProfileGroup
}

export const PROFILE_VARIABLES: readonly ProfileVariable[] = [
  { key: 'name', label: 'Name', group: 'Identity' },
  { key: 'language', label: 'Language', group: 'Identity' },
  { key: 'village', label: 'Village', group: 'Identity' },
  { key: 'district', label: 'District', group: 'Identity' },
  { key: 'state', label: 'State', group: 'Identity' },
  { key: 'crops', label: 'Crops', group: 'Produce' },
  { key: 'typicalQuantity', label: 'Typical quantity', group: 'Produce' },
  { key: 'usualMandi', label: 'Usual mandi', group: 'Selling' },
  { key: 'paymentPreference', label: 'Payment preference', group: 'Selling' },
  { key: 'transport', label: 'Transport', group: 'Logistics' },
  { key: 'storageAccess', label: 'Cold storage', group: 'Logistics' },
]

const capitalise = (v: string) => (v ? v[0].toUpperCase() + v.slice(1) : v)

/** A crop list with the inferred ones marked: "red onion, tomato, grapes (inferred)". */
function cropsValue(farmer: Farmer): FarmerValue | undefined {
  const crops = farmer.cropFacts
  if (crops.length === 0) return undefined
  const times = crops.map((c) => c.atMs ?? 0).filter((t) => t > 0)
  return {
    value: crops.map((c) => (c.source === 'said' ? c.name : `${c.name} (inferred)`)).join(', '),
    source: crops.some((c) => c.source === 'said') ? 'said' : 'inferred',
    atMs: times.length > 0 ? Math.max(...times) : undefined,
  }
}

/** One variable's current value in English letters, or undefined when the farmer has not told it yet. */
export function variableValue(farmer: Farmer, key: string): FarmerValue | undefined {
  if (key === 'crops') return cropsValue(farmer)
  const known = (farmer.profile as Record<string, FarmerValue | undefined>)[key]
  if (key === 'language') {
    // No stored language: the language of their calls, which G-One inferred.
    const v = known ?? (farmer.language ? { value: farmer.language, source: 'inferred' as const, atMs: farmer.lastCallMs } : undefined)
    return v && { ...v, value: languageLabel(v.value) }
  }
  if (known) return { ...known, value: capitalise(known.value) }
  // A variable added to the catalog after these fields: read the newest stored fact with the same field.
  const fact = farmer.facts.filter((f) => f.field === key).sort((a, b) => b.atMs - a.atMs)[0]
  const value = fact && englishText(fact.value.trim())
  return value ? { value: capitalise(value), source: fact.source, atMs: fact.atMs } : undefined
}

export interface ProfileRow {
  variable: ProfileVariable
  /** Undefined: "Not told yet". */
  value: FarmerValue | undefined
}

/** The catalog in group order, each row with its value; a group with no variables is left out. */
export function groupedProfile(farmer: Farmer, catalog: readonly ProfileVariable[] = PROFILE_VARIABLES): { group: ProfileGroup; rows: ProfileRow[] }[] {
  return PROFILE_GROUPS.map((group) => ({
    group,
    rows: catalog.filter((v) => v.group === group).map((variable) => ({ variable, value: variableValue(farmer, variable.key) })),
  })).filter((g) => g.rows.length > 0)
}

/** How many of the catalog's variables are known: 7 of 11. */
export function completeness(farmer: Farmer, catalog: readonly ProfileVariable[] = PROFILE_VARIABLES): { known: number; total: number } {
  return { known: catalog.filter((v) => variableValue(farmer, v.key) !== undefined).length, total: catalog.length }
}
