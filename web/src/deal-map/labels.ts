/** Pin grouping and label placement in screen pixels, so labels near one town do not sit on top of each other. */

export interface PinIn {
  x: number
  y: number
  label: string
}

export interface Cluster {
  /** Indices of the stops in this pin, in order. */
  stops: number[]
  x: number
  y: number
  label: string
}

/** Stops closer than `minDist` pixels share one pin, numbered "1–2". */
export function clusterPins(pins: readonly PinIn[], minDist = 22): Cluster[] {
  const clusters: Cluster[] = []
  pins.forEach((pin, i) => {
    const near = clusters.find((c) => Math.hypot(c.x - pin.x, c.y - pin.y) < minDist)
    if (near) {
      near.stops.push(i)
      if (!near.label.split(', ').includes(pin.label)) near.label = `${near.label}, ${pin.label}`
    } else {
      clusters.push({ stops: [i], x: pin.x, y: pin.y, label: pin.label })
    }
  })
  return clusters
}

export function clusterNumber(c: Cluster): string {
  const first = c.stops[0] + 1
  const last = c.stops[c.stops.length - 1] + 1
  return first === last ? String(first) : `${first}–${last}`
}

export interface Box {
  x: number
  y: number
  w: number
  h: number
}

export interface PlacedLabel {
  x: number
  y: number
  anchor: 'start' | 'middle' | 'end'
  box: Box
}

const overlaps = (a: Box, b: Box) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

/**
 * One label per pin, at the first of eight sides that stays on screen and clear of every pin and every label placed
 * before it. A label with no free side is left off; the pin number and the detail list still name the stop.
 */
export function placeLabels(clusters: readonly Cluster[], width: number, height: number, { charWidth = 7, lineHeight = 16, pinRadius = 12, avoid = [] as readonly Box[] } = {}): (PlacedLabel | null)[] {
  const taken: Box[] = [...avoid, ...clusters.map((c) => ({ x: c.x - pinRadius, y: c.y - pinRadius, w: pinRadius * 2 + (c.stops.length > 1 ? 14 : 0), h: pinRadius * 2 }))]
  const gap = pinRadius + 5
  return clusters.map((c) => {
    const w = c.label.length * charWidth + 6
    const h = lineHeight
    const candidates: { dx: number; dy: number; anchor: PlacedLabel['anchor'] }[] = [
      { dx: gap, dy: 0, anchor: 'start' },
      { dx: -gap, dy: 0, anchor: 'end' },
      { dx: 0, dy: -gap - 2, anchor: 'middle' },
      { dx: 0, dy: gap + 4, anchor: 'middle' },
      { dx: gap - 4, dy: -gap + 2, anchor: 'start' },
      { dx: gap - 4, dy: gap - 2, anchor: 'start' },
      { dx: -gap + 4, dy: -gap + 2, anchor: 'end' },
      { dx: -gap + 4, dy: gap - 2, anchor: 'end' },
    ]
    for (const { dx, dy, anchor } of candidates) {
      const x = c.x + dx
      const cy = c.y + dy
      const left = anchor === 'start' ? x - 3 : anchor === 'end' ? x - w + 3 : x - w / 2
      const box = { x: left, y: cy - h / 2, w, h }
      if (box.x < 4 || box.y < 4 || box.x + box.w > width - 4 || box.y + box.h > height - 4) continue
      if (taken.some((t) => overlaps(t, box))) continue
      taken.push(box)
      return { x, y: cy + h * 0.3, anchor, box }
    }
    return null
  })
}
