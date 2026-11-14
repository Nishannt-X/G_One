/**
 * Builds web/src/deal-map/indiaMapData.ts, the simplified map of India the deal map draws.
 *
 * Source: Natural Earth (public domain), version 5.x, downloaded once by hand from
 * https://github.com/nvkelso/natural-earth-vector/tree/master/geojson
 *   - ne_10m_admin_0_countries_ind.geojson: the India point-of-view country polygons. Its India feature is the national
 *     outline, which includes Jammu and Kashmir, Ladakh (with Gilgit-Baltistan and Aksai Chin) and Arunachal Pradesh as
 *     India shows them.
 *   - ne_10m_admin_1_states_provinces.geojson: states and union territories, used only for the internal state borders.
 *     The admin-1 file has no India point-of-view variant, so the areas it puts under other administrations are folded
 *     into the Indian union territory that India's official map shows them in (see FOLD below). Their line of control is
 *     then an internal edge of one union territory and is not drawn.
 *
 * Usage, from the repository root:
 *   curl -sfLO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries_ind.geojson
 *   curl -sfLO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
 *   bun web/scripts/build-india-map.ts <admin0_ind.geojson> <admin1.geojson> web/src/deal-map/indiaMapData.ts
 *
 * Only the generated TypeScript file is committed, never the downloads.
 */

type Pos = [number, number]
type Ring = Pos[]
interface Feature {
  properties: Record<string, unknown>
  geometry: { type: 'Polygon'; coordinates: Ring[] } | { type: 'MultiPolygon'; coordinates: Ring[][] }
}

/** Degrees. About 3 km; enough detail for a map a few hundred pixels wide, small enough to ship. */
const TOLERANCE = 0.015
/** Islands smaller than this (square degrees) are dropped, except the island groups we keep below. */
const MIN_AREA = 0.004
/** Small islands kept anyway so Lakshadweep is not left off. */
const KEEP_BOX = { west: 71.5, east: 74, south: 8, north: 12.5 }
const DECIMALS = 2
const LON_SCALE = Math.cos((22 * Math.PI) / 180)

/** Admin-1 areas outside Indian administration, folded into the union territory India's official map shows them in. */
const FOLD: Record<string, string> = {
  'PAK/Azad Kashmir': 'Jammu and Kashmir',
  'PAK/Northern Areas': 'Ladakh',
  'KAS/Kashmir': 'Ladakh',
}

const [admin0Path, admin1Path, outPath] = process.argv.slice(2)
if (!admin0Path || !admin1Path || !outPath) {
  console.error('usage: bun web/scripts/build-india-map.ts <admin0_ind.geojson> <admin1.geojson> <out.ts>')
  process.exit(1)
}

const read = async (path: string) => JSON.parse(await Bun.file(path).text()) as { features: Feature[] }
const polygonsOf = (f: Feature): Ring[][] => (f.geometry.type === 'Polygon' ? [f.geometry.coordinates] : f.geometry.coordinates)

function area(ring: Ring): number {
  let sum = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) sum += (ring[j][0] - ring[i][0]) * (ring[j][1] + ring[i][1])
  return Math.abs(sum / 2)
}

function perpendicular(p: Pos, a: Pos, b: Pos): number {
  const ax = a[0] * LON_SCALE
  const bx = b[0] * LON_SCALE
  const px = p[0] * LON_SCALE
  const dx = bx - ax
  const dy = b[1] - a[1]
  const len = Math.hypot(dx, dy)
  if (len === 0) return Math.hypot(px - ax, p[1] - a[1])
  return Math.abs(dy * px - dx * p[1] + bx * a[1] - b[1] * ax) / len
}

/** Douglas-Peucker, iterative. Keeps both ends. */
function simplify(line: Pos[], tolerance = TOLERANCE): Pos[] {
  if (line.length < 3) return line
  const keep = new Uint8Array(line.length)
  keep[0] = keep[line.length - 1] = 1
  const stack: [number, number][] = [[0, line.length - 1]]
  while (stack.length) {
    const [from, to] = stack.pop()!
    let max = 0
    let at = -1
    for (let i = from + 1; i < to; i++) {
      const d = perpendicular(line[i], line[from], line[to])
      if (d > max) {
        max = d
        at = i
      }
    }
    if (max > tolerance && at > 0) {
      keep[at] = 1
      stack.push([from, at], [at, to])
    }
  }
  return line.filter((_, i) => keep[i])
}

/** A closed ring split in two halves so Douglas-Peucker has two fixed ends, then joined again. */
function simplifyRing(ring: Ring): Ring {
  const open = ring.slice(0, -1)
  const half = Math.floor(open.length / 2)
  const a = simplify(open.slice(0, half + 1))
  const b = simplify([...open.slice(half), open[0]])
  return [...a.slice(0, -1), ...b.slice(0, -1)]
}

const round = (n: number) => Number(n.toFixed(DECIMALS))
const flat = (line: Pos[]) => {
  const out: number[] = []
  for (const [x, y] of line) {
    const rx = round(x)
    const ry = round(y)
    if (out.length && out[out.length - 2] === rx && out[out.length - 1] === ry) continue
    out.push(rx, ry)
  }
  return out
}

// 1. The national outline, India point of view.
const admin0 = await read(admin0Path)
const india = admin0.features.find((f) => f.properties.ADM0_A3 === 'IND')
if (!india) throw new Error('No India feature in the admin-0 file')
const inKeepBox = (ring: Ring) => ring.every(([x, y]) => x >= KEEP_BOX.west && x <= KEEP_BOX.east && y >= KEEP_BOX.south && y <= KEEP_BOX.north)
const outline: number[][] = []
for (const polygon of polygonsOf(india)) {
  const outer = polygon[0]
  const a = area(outer)
  if (a < MIN_AREA && !(inKeepBox(outer) && a > 0.0002)) continue
  const simple = a < MIN_AREA ? outer.slice(0, -1) : simplifyRing(outer)
  const coords = flat(simple)
  if (coords.length >= 6) outline.push(coords)
  if (polygon.length > 1) console.warn(`outline polygon with ${polygon.length - 1} hole(s); holes ignored`)
}

// 2. Internal borders: segments two different states share.
const admin1 = await read(admin1Path)
const key = (p: Pos) => `${p[0]},${p[1]}`
const segmentOwners = new Map<string, Set<string>>()
const segmentEnds = new Map<string, [Pos, Pos]>()
let states = 0
for (const f of admin1.features) {
  const country = String(f.properties.adm0_a3)
  const name = String(f.properties.name)
  const group = country === 'IND' ? name : FOLD[`${country}/${name}`]
  if (!group) continue
  states++
  for (const polygon of polygonsOf(f)) {
    for (const ring of polygon) {
      for (let i = 0; i + 1 < ring.length; i++) {
        const a = key(ring[i])
        const b = key(ring[i + 1])
        if (a === b) continue
        const k = a < b ? `${a}|${b}` : `${b}|${a}`
        if (!segmentOwners.has(k)) {
          segmentOwners.set(k, new Set())
          segmentEnds.set(k, [ring[i], ring[i + 1]])
        }
        segmentOwners.get(k)!.add(group)
      }
    }
  }
}

const adjacency = new Map<string, { to: string; seg: string; pos: Pos }[]>()
const points = new Map<string, Pos>()
for (const [k, owners] of segmentOwners) {
  if (owners.size < 2) continue
  const [a, b] = segmentEnds.get(k)!
  const ka = key(a)
  const kb = key(b)
  points.set(ka, a)
  points.set(kb, b)
  if (!adjacency.has(ka)) adjacency.set(ka, [])
  if (!adjacency.has(kb)) adjacency.set(kb, [])
  adjacency.get(ka)!.push({ to: kb, seg: k, pos: b })
  adjacency.get(kb)!.push({ to: ka, seg: k, pos: a })
}

const used = new Set<string>()
const chains: Pos[][] = []
function walk(start: string) {
  for (const first of adjacency.get(start)!) {
    if (used.has(first.seg)) continue
    const chain: Pos[] = [points.get(start)!]
    let edge: { to: string; seg: string; pos: Pos } | undefined = first
    while (edge) {
      used.add(edge.seg)
      chain.push(edge.pos)
      const next = adjacency.get(edge.to)!
      // Stop at junctions, so every chain ends where three borders meet and shared ends stay identical.
      edge = next.length === 2 ? next.find((e) => !used.has(e.seg)) : undefined
    }
    chains.push(chain)
  }
}
for (const [k, edges] of adjacency) if (edges.length !== 2) walk(k)
for (const k of adjacency.keys()) walk(k) // leftover closed loops (a state inside another)

const borders = chains.map((c) => flat(simplify(c))).filter((c) => c.length >= 4)

const fmt = (lines: number[][]) => `[\n${lines.map((l) => `  [${l.join(',')}],`).join('\n')}\n]`
const body = `/**
 * Simplified map of India, generated by web/scripts/build-india-map.ts. Do not edit by hand.
 *
 * Source: Natural Earth (public domain), ne_10m_admin_0_countries_ind (India point of view) for the outline and
 * ne_10m_admin_1_states_provinces for the internal state and union territory borders. Simplified to about
 * ${TOLERANCE} degrees and rounded to ${DECIMALS} decimals. Each line is a flat list: lon, lat, lon, lat, ...
 */

export const MAP_SOURCE = 'Natural Earth (public domain), India point-of-view boundaries, simplified'

/** Outer rings of the national outline: the mainland and the islands. */
export const OUTLINE: readonly (readonly number[])[] = ${fmt(outline)}

/** Borders between states and union territories. */
export const BORDERS: readonly (readonly number[])[] = ${fmt(borders)}
`
await Bun.write(outPath, body)
const points0 = outline.reduce((s, r) => s + r.length / 2, 0)
const points1 = borders.reduce((s, r) => s + r.length / 2, 0)
console.log(`${states} admin-1 areas, ${outline.length} outline rings (${points0} points), ${borders.length} border lines (${points1} points), ${body.length} bytes`)
