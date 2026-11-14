/**
 * A hand-made, simplified map of India for the deal map prototype: a rough outline from approximate longitude and
 * latitude points, filled with a dot grid. Not to scale and not a survey map; small islands are a few dots.
 */

export type LonLat = readonly [lon: number, lat: number]
export interface Point {
  x: number
  y: number
}

/** The box the map shows, in degrees. Every place on the map must fall inside it. */
export const BOUNDS = { west: 67.5, east: 97.8, south: 6.2, north: 37.6 } as const

/** Degrees of longitude are shorter than degrees of latitude this far north; one factor keeps the shape about right. */
const LON_SCALE = Math.cos((22 * Math.PI) / 180)

export const MAP_WIDTH = 600
export const MAP_HEIGHT = Math.round((MAP_WIDTH * (BOUNDS.north - BOUNDS.south)) / ((BOUNDS.east - BOUNDS.west) * LON_SCALE))

export function insideBounds([lon, lat]: LonLat): boolean {
  return lon >= BOUNDS.west && lon <= BOUNDS.east && lat >= BOUNDS.south && lat <= BOUNDS.north
}

/** Longitude and latitude to a point in the map's SVG box (x to the east, y to the south). */
export function project([lon, lat]: LonLat): Point {
  return {
    x: ((lon - BOUNDS.west) / (BOUNDS.east - BOUNDS.west)) * MAP_WIDTH,
    y: ((BOUNDS.north - lat) / (BOUNDS.north - BOUNDS.south)) * MAP_HEIGHT,
  }
}

/** The mainland, clockwise from the Kutch coast. Approximate points, enough for a recognisable dot map. */
export const INDIA_OUTLINE: readonly LonLat[] = [
  [68.3, 23.6], [69.0, 22.3], [70.2, 21.0], [71.4, 20.7], [72.2, 21.6], [72.7, 21.0], [72.8, 19.0], [73.3, 17.0],
  [73.8, 15.4], [74.8, 12.9], [75.8, 11.2], [76.3, 9.5], [77.5, 8.1], [78.2, 8.8], [79.3, 9.3], [79.85, 10.3],
  [79.8, 11.9], [80.3, 13.1], [80.1, 15.5], [81.2, 16.2], [82.3, 16.6], [83.3, 17.7], [84.9, 19.3], [86.5, 20.2],
  [87.0, 21.5], [88.2, 21.6], [89.0, 21.8], [88.8, 23.0], [88.6, 24.2], [88.1, 24.6], [88.4, 25.2], [88.1, 26.0],
  [88.4, 26.4], [89.8, 26.0], [90.5, 25.2], [92.0, 25.1], [92.3, 24.2], [91.8, 24.1], [91.2, 23.0], [91.8, 22.9],
  [92.3, 23.6], [92.6, 22.0], [93.2, 22.2], [93.4, 23.8], [94.2, 24.0], [94.6, 25.5], [95.2, 26.6], [96.0, 27.3],
  [97.3, 28.0], [96.0, 29.4], [94.5, 29.3], [93.0, 28.0], [91.9, 27.8], [91.6, 27.0], [89.8, 26.8], [88.9, 27.3],
  [88.8, 28.1], [88.0, 27.9], [88.1, 26.5], [85.0, 26.8], [83.5, 27.4], [81.6, 28.0], [80.1, 28.8], [80.4, 30.0],
  [79.0, 31.0], [78.7, 32.0], [79.4, 32.6], [79.0, 34.2], [79.5, 35.5], [77.8, 35.5], [77.0, 35.9], [75.9, 36.8],
  [74.5, 37.0], [73.0, 36.0], [73.4, 35.3], [73.9, 34.2], [74.0, 33.0], [74.6, 32.5], [75.3, 32.2], [74.6, 31.0],
  [73.9, 30.0], [72.9, 29.0], [70.8, 27.9], [70.0, 27.2], [69.6, 26.6], [70.3, 25.7], [69.6, 24.3],
]

/** A few dots for the island groups, so they are not left off. */
const ISLAND_DOTS: readonly LonLat[] = [
  [92.75, 13.4], [92.85, 12.8], [92.7, 12.2], [92.7, 11.6], [92.6, 11.0], [92.5, 10.6], [92.9, 9.2], [93.6, 8.2],
  [93.8, 7.4], [72.6, 11.0], [72.7, 10.5], [73.0, 8.3],
]

/** Even-odd ray test against the outline. */
export function insidePolygon([lon, lat]: LonLat, polygon: readonly LonLat[] = INDIA_OUTLINE): boolean {
  let inside = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i]
    const [xj, yj] = polygon[j]
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

/** The dot grid: every grid point inside the outline, plus the island dots, already projected. */
export function indiaDots(step = 0.5): Point[] {
  const dots: Point[] = []
  for (let lat = BOUNDS.south; lat <= BOUNDS.north; lat += step) {
    for (let lon = BOUNDS.west; lon <= BOUNDS.east; lon += step / LON_SCALE) {
      if (insidePolygon([lon, lat])) dots.push(project([lon, lat]))
    }
  }
  return dots.concat(ISLAND_DOTS.map(project))
}

/** A point on a quadratic Bézier curve. */
export function quadPoint(p0: Point, c: Point, p1: Point, t: number): Point {
  const u = 1 - t
  return { x: u * u * p0.x + 2 * u * t * c.x + t * t * p1.x, y: u * u * p0.y + 2 * u * t * c.y + t * t * p1.y }
}

/** The control point that bows a curve from a to b sideways by `bow` times its length (positive bows to the left of travel). */
export function bowControl(a: Point, b: Point, bow: number): Point {
  const mx = (a.x + b.x) / 2
  const my = (a.y + b.y) / 2
  const dx = b.x - a.x
  const dy = b.y - a.y
  return { x: mx + dy * bow, y: my - dx * bow }
}

/** Splits a quadratic curve at t into two quadratic curves (de Casteljau). */
export function splitQuad(p0: Point, c: Point, p1: Point, t = 0.5): [[Point, Point, Point], [Point, Point, Point]] {
  const a = { x: p0.x + (c.x - p0.x) * t, y: p0.y + (c.y - p0.y) * t }
  const b = { x: c.x + (p1.x - c.x) * t, y: c.y + (p1.y - c.y) * t }
  const m = quadPoint(p0, c, p1, t)
  return [[p0, a, m], [m, b, p1]]
}

const r = (n: number) => Math.round(n * 10) / 10

export function quadPath([p0, c, p1]: readonly [Point, Point, Point]): string {
  return `M${r(p0.x)} ${r(p0.y)}Q${r(c.x)} ${r(c.y)} ${r(p1.x)} ${r(p1.y)}`
}
