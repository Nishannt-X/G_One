/**
 * Map geometry for the deal map: our own projection of longitude and latitude onto the SVG box, the India outline and
 * state borders from the generated data file, and the view maths (fit to a route, points along a route).
 */
import { BORDERS, OUTLINE } from './indiaMapData'

export type LonLat = readonly [lon: number, lat: number]
export interface Point {
  x: number
  y: number
}
/** A rectangle in map units: what part of the map is on screen. */
export interface View {
  x: number
  y: number
  w: number
  h: number
}

/** The box the map shows, in degrees. Every place on the map must fall inside it. */
export const BOUNDS = { west: 67.8, east: 97.7, south: 6.4, north: 37.4 } as const

/** Degrees of longitude are shorter than degrees of latitude this far north; one factor keeps the shape about right. */
const LON_SCALE = Math.cos((22 * Math.PI) / 180)

export const MAP_WIDTH = 600
export const MAP_HEIGHT = Math.round((MAP_WIDTH * (BOUNDS.north - BOUNDS.south)) / ((BOUNDS.east - BOUNDS.west) * LON_SCALE))
export const FULL_VIEW: View = { x: 0, y: 0, w: MAP_WIDTH, h: MAP_HEIGHT }

export function insideBounds([lon, lat]: LonLat): boolean {
  return lon >= BOUNDS.west && lon <= BOUNDS.east && lat >= BOUNDS.south && lat <= BOUNDS.north
}

/** Longitude and latitude to a point in map units (x to the east, y to the south). Equirectangular, scaled at 22° N. */
export function project([lon, lat]: LonLat): Point {
  return {
    x: ((lon - BOUNDS.west) / (BOUNDS.east - BOUNDS.west)) * MAP_WIDTH,
    y: ((BOUNDS.north - lat) / (BOUNDS.north - BOUNDS.south)) * MAP_HEIGHT,
  }
}

/** Even-odd ray test of a point against one flat ring (lon, lat, lon, lat, ...). */
function insideRing([lon, lat]: LonLat, ring: readonly number[]): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
    const xi = ring[i]
    const yi = ring[i + 1]
    const xj = ring[j]
    const yj = ring[j + 1]
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

/** Inside India's outline (the simplified Natural Earth India point-of-view boundary)? */
export function insideIndia(at: LonLat): boolean {
  return OUTLINE.some((ring) => insideRing(at, ring))
}

const r1 = (n: number) => Math.round(n * 10) / 10

function flatPath(line: readonly number[], close: boolean): string {
  let d = ''
  for (let i = 0; i < line.length; i += 2) {
    const p = project([line[i], line[i + 1]])
    d += `${i ? 'L' : 'M'}${r1(p.x)} ${r1(p.y)}`
  }
  return close ? `${d}Z` : d
}

/** SVG path data in map units, built once. */
export const OUTLINE_PATH = OUTLINE.map((ring) => flatPath(ring, true)).join('')
export const BORDERS_PATH = BORDERS.map((line) => flatPath(line, false)).join('')

export function polylinePath(points: readonly Point[]): string {
  return points.map((p, i) => `${i ? 'L' : 'M'}${r1(p.x)} ${r1(p.y)}`).join('')
}

/**
 * The view that shows all the points with some padding, at the screen's aspect ratio (width / height), never smaller
 * than `minSpan` map units across and never larger than the whole map.
 */
export function fitView(points: readonly Point[], aspect: number, { pad = 0.14, minSpan = 90 } = {}): View {
  if (!points.length) return fitView([{ x: 0, y: 0 }, { x: MAP_WIDTH, y: MAP_HEIGHT }], aspect, { pad: 0, minSpan })
  const xs = points.map((p) => p.x)
  const ys = points.map((p) => p.y)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  let w = Math.max(minSpan, (maxX - minX) * (1 + 2 * pad))
  let h = Math.max(minSpan, (maxY - minY) * (1 + 2 * pad))
  // Grow one side to match the screen, so the map is never stretched.
  if (w / h < aspect) w = h * aspect
  else h = w / aspect
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  return { x: cx - w / 2, y: cy - h / 2, w, h }
}

/** The whole of India at this aspect ratio. */
export function fullView(aspect: number): View {
  return fitView([{ x: 0, y: 0 }, { x: MAP_WIDTH, y: MAP_HEIGHT }], aspect, { pad: 0.02, minSpan: 0 })
}

/** Linear blend between two views, for the zoom animation. */
export function mixView(a: View, b: View, t: number): View {
  return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, w: a.w + (b.w - a.w) * t, h: a.h + (b.h - a.h) * t }
}

/** The map-unit to screen-pixel transform for a view drawn into a box `width` by `height` pixels. */
export function viewTransform(view: View, width: number, height: number): { scale: number; tx: number; ty: number } {
  const scale = Math.min(width / view.w, height / view.h)
  // Centre the view if the box's aspect differs slightly.
  const tx = (width - view.w * scale) / 2 - view.x * scale
  const ty = (height - view.h * scale) / 2 - view.y * scale
  return { scale, tx, ty }
}

/** The point a fraction (0 to 1) of the way along a polyline, by length. */
export function pointAlong(points: readonly Point[], fraction: number): Point {
  if (points.length === 1) return points[0]
  const lengths: number[] = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    lengths.push(d)
    total += d
  }
  let remaining = Math.min(1, Math.max(0, fraction)) * total
  for (let i = 0; i < lengths.length; i++) {
    if (remaining <= lengths[i] || i === lengths.length - 1) {
      const t = lengths[i] === 0 ? 0 : Math.min(1, remaining / lengths[i])
      return { x: points[i].x + (points[i + 1].x - points[i].x) * t, y: points[i].y + (points[i + 1].y - points[i].y) * t }
    }
    remaining -= lengths[i]
  }
  return points[points.length - 1]
}

/** The first `fraction` of a polyline, by length. */
export function slicePolyline(points: readonly Point[], fraction: number): Point[] {
  if (points.length < 2 || fraction >= 1) return [...points]
  if (fraction <= 0) return [points[0]]
  let total = 0
  for (let i = 1; i < points.length; i++) total += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
  let remaining = fraction * total
  const out: Point[] = [points[0]]
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    if (remaining <= d) {
      const t = d === 0 ? 0 : remaining / d
      out.push({ x: points[i - 1].x + (points[i].x - points[i - 1].x) * t, y: points[i - 1].y + (points[i].y - points[i - 1].y) * t })
      return out
    }
    out.push(points[i])
    remaining -= d
  }
  return out
}
