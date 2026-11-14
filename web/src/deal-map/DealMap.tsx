import { useEffect, useMemo, useRef, useState } from 'react'
import { STOP_KIND, type Deal } from './deals'
import { BORDERS_PATH, fitView, fullView, mixView, OUTLINE_PATH, polylinePath, project, slicePolyline, viewTransform, type Point, type View } from './geo'
import { clusterNumber, clusterPins, placeLabels } from './labels'
import type { DemoView } from './playback'
import { doneStops, legPoints, routePoints, truckPoint } from './routes'

/** Before the first measurement (and in server rendering) the map assumes this size in pixels. */
const DEFAULT_SIZE = { w: 800, h: 640 }

function useSize<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [size, setSize] = useState(DEFAULT_SIZE)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect
      if (width > 0 && height > 0) setSize({ w: Math.round(width), h: Math.round(height) })
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  return [ref, size] as const
}

/** Glides from the view on screen to the target view; with reduced motion it jumps. */
function useAnimatedView(target: View, reduced: boolean): View {
  const [shown, setShown] = useState(target)
  const current = useRef(target)
  useEffect(() => {
    if (reduced) {
      current.current = target
      return
    }
    const from = current.current
    const start = performance.now()
    let raf = requestAnimationFrame(function frame(now) {
      const t = Math.min(1, (now - start) / 550)
      const eased = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2
      const view = mixView(from, target, eased)
      current.current = view
      setShown(view)
      if (t < 1) raf = requestAnimationFrame(frame)
    })
    return () => cancelAnimationFrame(raf)
  }, [target, reduced])
  return reduced ? target : shown
}

function Truck({ at }: { at: Point }) {
  return (
    <g transform={`translate(${at.x - 13} ${at.y - 36})`} aria-hidden="true" data-layer="truck">
      <rect x={0} y={2} width={17} height={11} rx={2} fill="var(--accent)" stroke="var(--ink)" strokeWidth={1.5} />
      <path d="M17 6h5l4 4v3h-9z" fill="var(--accent)" stroke="var(--ink)" strokeWidth={1.5} strokeLinejoin="round" />
      <circle cx={6} cy={15} r={2.6} fill="var(--ink)" />
      <circle cx={21} cy={15} r={2.6} fill="var(--ink)" />
      <path d="M13 19v14" stroke="var(--ink)" strokeWidth={1.5} strokeDasharray="2 2" />
    </g>
  )
}

const HALO = { paintOrder: 'stroke', stroke: 'var(--surface)', strokeWidth: 4, strokeLinejoin: 'round' } as const

export interface DealMapProps {
  deals: readonly Deal[]
  selected: Deal | null
  /** A deal the list is pointing at (hover or focus), drawn on top in the overview. */
  highlight?: string | null
  /** Demo playback; null shows the selected deal whole. */
  demo?: DemoView | null
  reduced: boolean
}

/** India with every deal's route, or one deal's route zoomed in with numbered stops. */
export default function DealMap({ deals, selected, highlight = null, demo = null, reduced }: DealMapProps) {
  const [ref, size] = useSize<HTMLDivElement>()
  const aspect = size.w / size.h
  // The deal whose route the person zoomed out from; any new selection zooms in again.
  const [wholeFor, setWholeFor] = useState<string | null>(null)
  const showWhole = !selected || wholeFor === selected.id
  const selectedId = selected?.id ?? null
  const target = useMemo(() => {
    const deal = deals.find((d) => d.id === selectedId)
    return !deal || showWhole ? fullView(aspect) : fitView(routePoints(deal), aspect)
  }, [deals, selectedId, showWhole, aspect])
  const view = useAnimatedView(target, reduced)
  const { scale, tx, ty } = viewTransform(view, size.w, size.h)
  const toScreen = (p: Point): Point => ({ x: p.x * scale + tx, y: p.y * scale + ty })

  const routes = deals.map((deal) => ({ deal, d: polylinePath(routePoints(deal).map(toScreen)) }))
  const highlighted = selected ? null : routes.find((r) => r.deal.id === highlight) ?? null

  let detail = null
  if (selected) {
    const reached = demo ? demo.reached : doneStops(selected)
    const truck = demo ? demo.truck : selected.truck ?? null
    const current = demo ? demo.current : null
    const legs = selected.stops.map((_, i) => (i === 0 ? null : legPoints(selected, i).map(toScreen)))
    const pins = selected.stops.map((s) => ({ ...toScreen(project(s.at)), label: s.short }))
    const clusters = clusterPins(pins)
    const truckAt = truck ? toScreen(truckPoint(selected, truck)) : null
    // Keep labels off the truck and from under the zoom button in the top right corner.
    const avoid = [{ x: size.w - 140, y: 0, w: 140, h: 50 }, ...(truckAt ? [{ x: truckAt.x - 15, y: truckAt.y - 38, w: 30, h: 22 }] : [])]
    const labels = placeLabels(clusters, size.w, size.h, { avoid })

    detail = (
      <g data-layer="route" aria-hidden="true">
        {legs.map((points, i) => {
          if (!points) return null
          const done = i < reached
          const partial = truck && truck.leg === i && !done ? slicePolyline(points, truck.fraction) : null
          return (
            <g key={i}>
              <path d={polylinePath(points)} fill="none" stroke="var(--surface)" strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" opacity={0.8} />
              {done ? (
                <path d={polylinePath(points)} fill="none" stroke="var(--brand)" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" data-leg="done" />
              ) : (
                <path d={polylinePath(points)} fill="none" stroke="var(--line-strong)" strokeWidth={2.5} strokeDasharray="2 6" strokeLinecap="round" strokeLinejoin="round" data-leg="planned" />
              )}
              {partial && partial.length > 1 && <path d={polylinePath(partial)} fill="none" stroke="var(--brand)" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" />}
            </g>
          )
        })}
        {clusters.map((c, ci) => {
          const isReached = c.stops.some((i) => i < reached)
          const isCurrent = current !== null && c.stops.includes(current)
          const trouble = c.stops.some((i) => i < reached && selected.stops[i].difficulty)
          const text = clusterNumber(c)
          const w = text.length > 1 ? 12 + text.length * 6.5 : 22
          const label = labels[ci]
          return (
            <g key={ci} data-stop={text}>
              {isCurrent && <circle cx={c.x} cy={c.y} r={18} fill="var(--accent)" opacity={0.28} className="motion-safe:animate-beat [transform-box:fill-box] [transform-origin:center]" />}
              <rect
                x={c.x - w / 2}
                y={c.y - 11}
                width={w}
                height={22}
                rx={11}
                fill={isCurrent ? 'var(--accent)' : isReached ? 'var(--brand)' : 'var(--surface)'}
                stroke={isReached || isCurrent ? 'var(--surface)' : 'var(--line-strong)'}
                strokeWidth={2}
                strokeDasharray={isReached || isCurrent ? undefined : '3 2'}
              />
              <text x={c.x} y={c.y + 4} textAnchor="middle" className="text-[12px] font-bold" fill={isCurrent ? 'var(--on-accent)' : isReached ? 'var(--on-brand)' : 'var(--ink)'}>
                {text}
              </text>
              {trouble && (
                <g>
                  <circle cx={c.x + w / 2} cy={c.y - 10} r={7} fill="var(--warn)" stroke="var(--surface)" strokeWidth={1.5} />
                  <text x={c.x + w / 2} y={c.y - 6.5} textAnchor="middle" className="text-[10px] font-bold" fill="var(--warn-soft)">!</text>
                </g>
              )}
              {label && (
                <text x={label.x} y={label.y} textAnchor={label.anchor} className="text-[12.5px] font-semibold" fill="var(--ink)" style={HALO}>
                  {c.label}
                </text>
              )}
            </g>
          )
        })}
        {truckAt && <Truck at={truckAt} />}
      </g>
    )
  }

  const description = selected
    ? `Route of the ${selected.crop.toLowerCase()} deal from ${selected.lane.from} to ${selected.lane.to}, ${selected.stops.length} stops: ${selected.stops.map((s, i) => `${i + 1}, ${STOP_KIND[s.kind]}, ${s.place}`).join('; ')}.`
    : `Routes of all ${deals.length} example deals across India.`

  return (
    <div className="relative h-full min-h-0 w-full" ref={ref}>
      <svg viewBox={`0 0 ${size.w} ${size.h}`} className="absolute inset-0 block h-full w-full" role="img" aria-labelledby="deal-map-title deal-map-desc">
        <title id="deal-map-title">Map of India with simulated deal routes</title>
        <desc id="deal-map-desc">{description}</desc>
        <rect width={size.w} height={size.h} fill="color-mix(in oklab, var(--buyer) 7%, var(--surface))" />
        <g transform={`matrix(${scale} 0 0 ${scale} ${tx} ${ty})`} data-layer="base">
          <path d={OUTLINE_PATH} fill="color-mix(in oklab, var(--brand) 9%, var(--surface))" stroke="var(--line-strong)" strokeWidth={1} vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
          <path d={BORDERS_PATH} fill="none" stroke="var(--line-strong)" strokeOpacity={0.45} strokeWidth={0.8} vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
        </g>
        <g data-layer="routes" aria-hidden="true">
          {routes.map(({ deal, d }) => (
            <path
              key={deal.id}
              d={d}
              fill="none"
              stroke={selected ? 'var(--line-strong)' : 'var(--brand)'}
              strokeOpacity={selected ? (deal.id === selected.id ? 0 : 0.18) : 0.55}
              strokeWidth={selected ? 1.5 : 2}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ))}
          {!selected &&
            routes.map(({ deal }) => {
              const a = toScreen(project(deal.stops[0].at))
              const b = toScreen(project(deal.stops[deal.stops.length - 1].at))
              return (
                <g key={deal.id}>
                  <circle cx={a.x} cy={a.y} r={4} fill="var(--farmer)" stroke="var(--surface)" strokeWidth={1.5} />
                  <rect x={b.x - 4} y={b.y - 4} width={8} height={8} rx={1.5} fill="var(--buyer)" stroke="var(--surface)" strokeWidth={1.5} />
                </g>
              )
            })}
          {highlighted && (
            <g>
              <path d={highlighted.d} fill="none" stroke="var(--surface)" strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" />
              <path d={highlighted.d} fill="none" stroke="var(--accent)" strokeWidth={4} strokeLinecap="round" strokeLinejoin="round" />
            </g>
          )}
        </g>
        {detail}
      </svg>
      {selected && (
        <div className="absolute top-2 right-2 flex gap-1.5">
          {showWhole ? (
            <button type="button" className="btn-quiet btn-sm shadow-card" onClick={() => setWholeFor(null)}>Zoom to route</button>
          ) : (
            <button type="button" className="btn-quiet btn-sm shadow-card" onClick={() => setWholeFor(selected.id)}>Reset view</button>
          )}
        </div>
      )}
    </div>
  )
}
