/**
 * Numbers copied from the repository's synthetic freight table
 * (backend/src/main/resources/advisory/freight-table.sample.json, label "synthetic sample"). Only the truck row is
 * needed here. Road distances come from the deal data and are rough synthetic estimates.
 */
export const FREIGHT_SAMPLE_LABEL = 'synthetic sample'

export const SAMPLE_TRUCK = { id: 'truck', en: 'truck', capacityQuintals: 100, rupeesPerKm: 55, minimumTrip: 2500 } as const

/** The truck's share of a full trip for a part load, never below the table's minimum trip charge. */
export function freightRupees(km: number, quantityQuintals: number, truck = SAMPLE_TRUCK): number {
  const share = Math.min(1, quantityQuintals / truck.capacityQuintals)
  return Math.max(truck.minimumTrip, Math.round(km * truck.rupeesPerKm * share))
}
