import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import freightTable from '../../../backend/src/main/resources/advisory/freight-table.sample.json'
import DealMapPage, { SIMULATED_BANNER } from '../views/DealMapPage'
import { browse } from './browse'
import { DEALS, kmOf, minutesOf, STATUS, totalOf, type DealStatus } from './deals'
import { freightRupees, SAMPLE_TRUCK } from './freight'
import { BOUNDS, fitView, FULL_VIEW, insideBounds, insideIndia, MAP_HEIGHT, MAP_WIDTH, pointAlong, project, slicePolyline, viewTransform } from './geo'
import { BORDERS, OUTLINE } from './indiaMapData'
import { clusterNumber, clusterPins, placeLabels } from './labels'
import { LANGUAGES } from './languages'
import { demoSteps, demoView, displayPosition, INITIAL_PLAYER, locate, reducePlayer, stepStart, totalSeconds, type PlayerState } from './playback'
import { legPoints, routePoints, truckPoint } from './routes'

const textOf = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/&#x27;/g, "'").replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/\s+/g, ' ')
const HONEST = /real[- ]time|instant|verified|legally binding|blocks? scams?|money saved|e-?NAM|guarantee(?!s no)/i

describe('India map data', () => {
  it('is small enough to ship and has an outline and state borders', async () => {
    const size = (await Bun.file(new URL('./indiaMapData.ts', import.meta.url)).arrayBuffer()).byteLength
    expect(size).toBeLessThan(150_000)
    expect(OUTLINE.length).toBeGreaterThan(5)
    expect(BORDERS.length).toBeGreaterThan(30)
    for (const line of [...OUTLINE, ...BORDERS]) {
      expect(line.length % 2).toBe(0)
      for (let i = 0; i < line.length; i += 2) expect(insideBounds([line[i], line[i + 1]])).toBe(true)
    }
  })

  it("follows India's official boundaries: all of Jammu and Kashmir, Ladakh and Arunachal Pradesh are inside", () => {
    const inside = { Delhi: [77.21, 28.61], Srinagar: [74.8, 34.08], Leh: [77.58, 34.15], Muzaffarabad: [73.47, 34.37], Gilgit: [74.31, 35.92], 'Aksai Chin': [79.5, 35.2], Tawang: [91.86, 27.59], 'Port Blair': [92.73, 11.62], Kanyakumari: [77.54, 8.09] } as const
    const outside = { Lahore: [74.35, 31.55], Dhaka: [90.4, 23.8], Kathmandu: [85.3, 27.7], Colombo: [79.86, 6.93], Thimphu: [89.64, 27.47], 'Arabian Sea': [65, 15] } as const
    for (const [name, at] of Object.entries(inside)) expect([name, insideIndia(at)]).toEqual([name, true])
    for (const [name, at] of Object.entries(outside)) expect([name, insideIndia(at)]).toEqual([name, false])
  })
})

describe('projection and view', () => {
  it('projects the bounds to the corners of the map box', () => {
    expect(project([BOUNDS.west, BOUNDS.north])).toEqual({ x: 0, y: 0 })
    const se = project([BOUNDS.east, BOUNDS.south])
    expect(se.x).toBeCloseTo(MAP_WIDTH)
    expect(se.y).toBeCloseTo(MAP_HEIGHT)
  })

  it('fits a route with padding at the screen aspect, and never zooms closer than the minimum span', () => {
    for (const deal of DEALS) {
      const points = routePoints(deal)
      for (const aspect of [16 / 9, 1, 390 / 480]) {
        const view = fitView(points, aspect)
        expect(view.w / view.h).toBeCloseTo(aspect)
        expect(view.w).toBeGreaterThanOrEqual(90 - 1e-9)
        for (const p of points) {
          expect(p.x).toBeGreaterThan(view.x)
          expect(p.x).toBeLessThan(view.x + view.w)
          expect(p.y).toBeGreaterThan(view.y)
          expect(p.y).toBeLessThan(view.y + view.h)
        }
      }
    }
  })

  it('maps a view onto the screen box and back', () => {
    const view = fitView(routePoints(DEALS[0]), 800 / 600)
    const { scale, tx, ty } = viewTransform(view, 800, 600)
    expect(view.x * scale + tx).toBeCloseTo(0)
    expect((view.y + view.h) * scale + ty).toBeCloseTo(600)
    const whole = viewTransform(FULL_VIEW, MAP_WIDTH, MAP_HEIGHT)
    expect(whole).toEqual({ scale: 1, tx: 0, ty: 0 })
  })

  it('walks and slices a polyline by length', () => {
    const line = [{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 30 }]
    expect(pointAlong(line, 0)).toEqual({ x: 0, y: 0 })
    expect(pointAlong(line, 0.5)).toEqual({ x: 10, y: 10 })
    expect(pointAlong(line, 1)).toEqual({ x: 10, y: 30 })
    expect(slicePolyline(line, 0.25)).toEqual([{ x: 0, y: 0 }, { x: 10, y: 0 }])
    expect(slicePolyline(line, 0.5).at(-1)).toEqual({ x: 10, y: 10 })
  })
})

describe('pins and labels', () => {
  it('groups stops that sit on top of each other into one numbered pin', () => {
    const clusters = clusterPins([{ x: 100, y: 100, label: 'Farm' }, { x: 108, y: 104, label: 'Weighbridge' }, { x: 300, y: 100, label: 'Hub' }])
    expect(clusters.map(clusterNumber)).toEqual(['1–2', '3'])
    expect(clusters[0].label).toBe('Farm, Weighbridge')
  })

  it('places labels on screen, clear of each other and of every pin', () => {
    const pins = [{ x: 200, y: 200, label: 'Gadag weighbridge' }, { x: 240, y: 200, label: 'Hyderabad hub' }, { x: 220, y: 240, label: 'Near Rajahmundry' }, { x: 790, y: 10, label: 'Corner' }]
    const clusters = clusterPins(pins)
    const placed = placeLabels(clusters, 800, 600)
    const boxes = placed.filter((p) => p !== null).map((p) => p!.box)
    expect(boxes.length).toBeGreaterThanOrEqual(3)
    for (const [i, a] of boxes.entries()) {
      expect(a.x).toBeGreaterThanOrEqual(4)
      expect(a.x + a.w).toBeLessThanOrEqual(796)
      for (const b of boxes.slice(i + 1)) expect(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h).toBe(false)
      for (const c of clusters) expect(c.x > a.x && c.x < a.x + a.w && c.y > a.y && c.y < a.y + a.h).toBe(false)
    }
  })
})

describe('deal data', () => {
  it('has about ten deals with unique ids, two playable demos, and every status at least once', () => {
    expect(DEALS.length).toBeGreaterThanOrEqual(10)
    expect(new Set(DEALS.map((d) => d.id)).size).toBe(DEALS.length)
    expect(DEALS.filter((d) => d.demo).map((d) => d.id)).toEqual(['onion-gadag-kolkata', 'tomato-nashik-chennai'])
    expect(new Set(DEALS.map((d) => d.status))).toEqual(new Set(Object.keys(STATUS) as DealStatus[]))
  })

  it('keeps every deal whole: three or more stops inside India, hand-offs and records named, times in order', () => {
    for (const deal of DEALS) {
      expect(deal.stops.length).toBeGreaterThanOrEqual(3)
      expect(deal.stops[0].km).toBe(0)
      let previous = minutesOf(deal.call.at)
      for (const stop of deal.stops) {
        for (const at of [stop.at, ...(stop.via ?? [])]) {
          expect(insideBounds(at)).toBe(true)
          expect([deal.id, stop.place, insideIndia(at)]).toEqual([deal.id, stop.place, true])
        }
        expect(stop.handler.role.length).toBeGreaterThan(2)
        expect(stop.handler.name.length).toBeGreaterThan(2)
        expect(stop.handoff.length).toBeGreaterThan(10)
        expect(stop.records.length).toBeGreaterThan(0)
        for (const r of stop.records) expect(r.item.length * r.heldBy.length).toBeGreaterThan(0)
        for (const stamp of [stop.arrive, stop.depart]) {
          if (!stamp) continue
          expect(minutesOf(stamp)).toBeGreaterThanOrEqual(previous)
          previous = minutesOf(stamp)
        }
        if (stop.difficulty) for (const field of Object.values(stop.difficulty)) expect(String(field).length).toBeGreaterThan(0)
      }
      // Once a stop is planned, every later stop is planned too.
      const planned = deal.stops.map((s) => !!s.planned)
      expect(planned).toEqual([...planned].sort((a, b) => Number(a) - Number(b)))
      expect(LANGUAGES[deal.farmer.language]).toBeDefined()
      expect(LANGUAGES[deal.buyer.language]).toBeDefined()
    }
  })

  it('states every price with its basis, and works totals and freight out from the numbers', () => {
    for (const deal of DEALS) {
      expect(deal.price.per).toBe(deal.quantity.unit)
      expect(deal.price.basis).toMatch(new RegExp(`^per (10 kg )?${deal.price.per}`))
      expect(totalOf(deal)).toBe(deal.quantity.amount * deal.price.rupees)
      expect(deal.freight.rupees).toBe(freightRupees(kmOf(deal), deal.quantity.quintals))
      expect(deal.payment.method).toMatch(/simulated/)
      if (deal.payment.state === 'paid' && !deal.stops.some((s) => s.difficulty?.costImpact.includes('less'))) expect(deal.payment.rupees).toBe(totalOf(deal))
    }
  })

  it('puts a truck on the road for deals in transit or delayed, on a real leg', () => {
    for (const deal of DEALS) {
      if (deal.status === 'in-transit' || deal.status === 'delayed') {
        expect(deal.truck).toBeDefined()
        const { leg, fraction } = deal.truck!
        expect(deal.stops[leg].planned).toBe(true)
        expect(deal.stops[leg - 1].planned).toBeFalsy()
        expect(fraction).toBeGreaterThanOrEqual(0)
        expect(fraction).toBeLessThanOrEqual(1)
        const p = truckPoint(deal, deal.truck!)
        expect(Number.isFinite(p.x) && Number.isFinite(p.y)).toBe(true)
      } else {
        expect(deal.truck).toBeUndefined()
      }
    }
    expect(legPoints(DEALS[0], 0)).toHaveLength(1)
  })

  it('holds no phone numbers, no overclaims, and keeps G-One out of the role of weigher, grader or payer', () => {
    const copy = JSON.stringify(DEALS)
    expect(copy).not.toMatch(/(\+91[\s-]?)?\b[6-9]\d{9}\b/)
    expect(copy).not.toMatch(/\d{10,}/)
    expect(copy).not.toMatch(HONEST)
    for (const deal of DEALS) for (const stop of deal.stops) expect(stop.handler.name).not.toMatch(/G-One/)
  })

  it('copies the truck rate from the sample freight table', () => {
    const truck = freightTable.vehicles.find((v) => v.id === 'truck')!
    expect(freightTable.label).toBe('synthetic sample')
    expect(SAMPLE_TRUCK).toMatchObject({ capacityQuintals: truck.capacityQuintals, rupeesPerKm: truck.rupeesPerKm, minimumTrip: truck.minimumTrip })
    expect(freightRupees(100, 100)).toBe(5500)
    expect(freightRupees(10, 10)).toBe(2500)
  })
})

describe('demo player', () => {
  const deal = DEALS.find((d) => d.id === 'onion-gadag-kolkata')!
  const steps = demoSteps(deal)
  const total = totalSeconds(steps)
  const run = (state: PlayerState, ...actions: Parameters<typeof reducePlayer>[1][]) => actions.reduce((s, a) => reducePlayer(s, a, steps), state)

  it('builds a call step, then a drive and a stop for every stop', () => {
    expect(steps[0].kind).toBe('call')
    expect(steps.filter((s) => s.kind === 'stop')).toHaveLength(deal.stops.length)
    expect(steps.filter((s) => s.kind === 'travel')).toHaveLength(deal.stops.length - 1)
  })

  it('advances with time, at the chosen speed, and stops at the end', () => {
    let state = run(INITIAL_PLAYER, { type: 'play' })
    expect(state).toMatchObject({ playing: true, started: true, t: 0 })
    state = run(state, { type: 'tick', dt: 0.1 })
    expect(state.t).toBeCloseTo(0.1)
    state = run(state, { type: 'speed', speed: 4 }, { type: 'tick', dt: 0.1 })
    expect(state.t).toBeCloseTo(0.5)
    state = run(state, { type: 'tick', dt: 5 }) // a long gap counts as a quarter second at most
    expect(state.t).toBeCloseTo(1.5)
    for (let i = 0; i < 1000 && state.playing; i++) state = run(state, { type: 'tick', dt: 0.25 })
    expect(state).toMatchObject({ t: total, playing: false })
    expect(run(state, { type: 'play' }).t).toBe(0)
  })

  it('steps one step at a time, restarts from zero and resets to the whole deal', () => {
    let state = run(INITIAL_PLAYER, { type: 'step' }, { type: 'step' })
    expect(state).toMatchObject({ t: stepStart(steps, 2), playing: false, started: true })
    state = run(state, { type: 'speed', speed: 2 }, { type: 'restart' })
    expect(state).toMatchObject({ t: 0, playing: true, speed: 2 })
    expect(run(state, { type: 'reset' })).toEqual({ ...INITIAL_PLAYER, speed: 2 })
    expect(run(INITIAL_PLAYER, { type: 'pause' }, { type: 'tick', dt: 0.2 }).t).toBe(0)
  })

  it('reveals stops in order, highlights the current one, shows the difficulty when reached, and ends on the final status', () => {
    let reached = 0
    const statuses: string[] = []
    for (let i = 0; i <= steps.length; i++) {
      const view = demoView(deal, steps, { index: i, fraction: 0.5 })
      expect(view.reached).toBeGreaterThanOrEqual(reached)
      reached = view.reached
      if (statuses.at(-1) !== view.status) statuses.push(view.status)
      const step = steps[i]
      if (step?.kind === 'stop') expect(view.current).toBe(step.stop)
      if (step?.kind === 'travel') expect(view.truck).toEqual({ leg: step.stop, fraction: 0.5 })
    }
    expect(reached).toBe(deal.stops.length)
    expect(statuses[0]).toBe('awaiting-pickup')
    expect(statuses).toContain('delayed')
    expect(statuses.at(-1)).toBe(deal.status)
    const tyre = deal.stops.findIndex((s) => s.kind === 'roadside')
    const atTyre = steps.findIndex((s) => s.kind === 'stop' && s.stop === tyre)
    expect(demoView(deal, steps, { index: atTyre - 1, fraction: 0.9 }).reached).toBe(tyre)
    expect(demoView(deal, steps, { index: atTyre, fraction: 0 }).reached).toBe(tyre + 1)
  })

  it('jumps from step to step with reduced motion: the truck is never shown part-way', () => {
    const travel = steps.findIndex((s) => s.kind === 'travel')
    const mid = locate(steps, stepStart(steps, travel) + steps[travel].seconds / 3)
    expect(mid.fraction).toBeCloseTo(1 / 3)
    expect(displayPosition(mid, true)).toEqual({ index: travel, fraction: 1 })
    expect(displayPosition(mid, false)).toEqual(mid)
    expect(demoView(deal, steps, displayPosition(mid, true)).truck?.fraction).toBe(1)
  })

  it('every demo deal ends on its own status', () => {
    for (const d of DEALS.filter((x) => x.demo)) {
      const s = demoSteps(d)
      expect(d.stops.at(-1)!.statusAfter).toBe(d.status)
      expect(demoView(d, s, { index: s.length, fraction: 1 })).toMatchObject({ status: d.status, reached: d.stops.length, ended: true })
    }
  })
})

describe('deal browser', () => {
  it('opens a deal and goes back to the list, remembering which item to focus', () => {
    let state = { selected: null as string | null, lastOpened: null as string | null }
    state = browse(state, { type: 'select', id: 'chilli-guntur-delhi' })
    expect(state).toEqual({ selected: 'chilli-guntur-delhi', lastOpened: 'chilli-guntur-delhi' })
    state = browse(state, { type: 'back' })
    expect(state).toEqual({ selected: null, lastOpened: 'chilli-guntur-delhi' })
  })
})

describe('Deal map page', () => {
  const svgOf = (html: string) => html.slice(html.indexOf('<svg viewBox'), html.indexOf('</svg>', html.indexOf('<svg viewBox')))

  it('lists every deal as a button, with the simulated banner and the map', () => {
    const html = renderToStaticMarkup(<DealMapPage />)
    const text = textOf(html)
    expect(text).toContain(SIMULATED_BANNER)
    expect(SIMULATED_BANNER).toMatch(/made-up example deals, not real transactions, transporters or payments/)
    for (const deal of DEALS) expect(html).toContain(`id="deal-item-${deal.id}"`)
    expect(text).toContain(`All deals (${DEALS.length})`)
    expect((html.match(/<button[^>]*id="deal-item-/g) ?? []).length).toBe(DEALS.length)
    expect(text).not.toMatch(HONEST)
    expect(svgOf(html)).toContain('data-layer="base"')
  })

  it('shows a delivered deal stop by stop, with hand-offs, records and the difficulty, and no list', () => {
    const deal = DEALS.find((d) => d.id === 'onion-gadag-kolkata')!
    const html = renderToStaticMarkup(<DealMapPage initialDealId={deal.id} />)
    const text = textOf(html)
    expect(text).not.toContain(`All deals (${DEALS.length})`)
    expect(text).toContain('All deals')
    expect(text).toContain('Deal agreed on a call')
    expect(text).toContain('No transcript, no audio and no phone numbers are stored.')
    for (const stop of deal.stops) {
      expect(text).toContain(stop.handoff)
      expect(text).toContain(stop.handler.name)
    }
    expect(text).toContain(deal.price.basis.replace(/'/g, "'"))
    expect((text.match(/Difficulty, about/g) ?? []).length).toBe(2)
    expect(text).toMatch(/Play the journey/)
    expect(html).toContain('aria-label="Speed"')
    expect(html).toMatch(/aria-pressed="true"[^>]*>1x/)
    expect(text).not.toMatch(HONEST)
  })

  it('draws numbered stops on the route and never puts G-One on the map', () => {
    for (const deal of DEALS) {
      const svg = svgOf(renderToStaticMarkup(<DealMapPage initialDealId={deal.id} />))
      expect(svg).toContain('data-layer="route"')
      expect(svg).not.toMatch(/G-One/)
      expect((svg.match(/data-stop="/g) ?? []).length).toBeGreaterThan(0)
      expect(svg.includes('data-layer="truck"')).toBe(!!deal.truck)
    }
  })

  it('keeps the layout safe on a phone: one column by default, nothing wider than the screen outside desktop classes', () => {
    const html = renderToStaticMarkup(<DealMapPage initialDealId="tomato-nashik-chennai" />)
    const classes = [...html.matchAll(/class="([^"]+)"/g)].flatMap((m) => m[1].split(/\s+/))
    const wide = classes.filter((c) => /^(min-w|w)-\[(\d+)(px|rem)\]$/.test(c)).filter((c) => {
      const [, , n, unit] = c.match(/^(min-w|w)-\[(\d+)(px|rem)\]$/)!
      return (unit === 'rem' ? Number(n) * 16 : Number(n)) > 358
    })
    expect(wide).toEqual([])
    expect(html).toContain('lg:grid-cols-[minmax(0,1fr)_minmax(360px,420px)]')
    expect(html).toContain('min-h-[300px]')
  })
})
