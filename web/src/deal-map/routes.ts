import type { Deal } from './deals'
import { pointAlong, project, type Point } from './geo'

/** The road from stop `leg - 1` to stop `leg`, in map units. Leg 0 is just the first stop. */
export function legPoints(deal: Deal, leg: number): Point[] {
  const stop = deal.stops[leg]
  if (leg === 0) return [project(stop.at)]
  return [deal.stops[leg - 1].at, ...(stop.via ?? []), stop.at].map(project)
}

/** Every point of the route, first stop to last, in map units. */
export function routePoints(deal: Deal): Point[] {
  return deal.stops.flatMap((_, i) => (i === 0 ? legPoints(deal, 0) : legPoints(deal, i).slice(1)))
}

/** Where the truck is, in map units. */
export function truckPoint(deal: Deal, truck: { leg: number; fraction: number }): Point {
  return pointAlong(legPoints(deal, truck.leg), truck.fraction)
}

/** How many stops are done, for a deal shown whole: every stop not marked planned. */
export function doneStops(deal: Deal): number {
  const firstPlanned = deal.stops.findIndex((s) => s.planned)
  return firstPlanned === -1 ? deal.stops.length : firstPlanned
}
