/**
 * Numbers copied from the repository's synthetic freight table
 * (backend/src/main/resources/advisory/freight-table.sample.json, label "synthetic sample"). Only the truck row is
 * needed here. The long-haul distances below are not in that table; they are rough synthetic estimates for the prototype.
 */
export const FREIGHT_SAMPLE_LABEL = 'synthetic sample'

export const SAMPLE_TRUCK = { id: 'truck', en: 'truck', capacityQuintals: 100, rupeesPerKm: 55, minimumTrip: 2500 } as const

/** Rough road distance a truck covers in a day, for the ETA. A synthetic assumption, not a measured figure. */
export const KM_PER_DAY = 450

/** The truck's share of a full trip for a part load, never below the table's minimum trip charge. */
export function freightRupees(km: number, quantityQuintals: number, truck = SAMPLE_TRUCK): number {
  const share = Math.min(1, quantityQuintals / truck.capacityQuintals)
  return Math.max(truck.minimumTrip, Math.round(km * truck.rupeesPerKm * share))
}

/** Hours on the road at the assumed daily distance, rounded to the hour. */
export function etaHours(km: number, kmPerDay = KM_PER_DAY): number {
  return Math.max(1, Math.round((km / kmPerDay) * 24))
}

/** "about 4 days" or "about 9 hours". */
export function etaLabel(hours: number): string {
  if (hours < 36) return `about ${hours} hours`
  return `about ${Math.round(hours / 24)} days`
}
