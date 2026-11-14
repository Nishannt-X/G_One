import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import DealMapPage, { SIMULATED_BANNER } from '../views/DealMapPage'
import { etaHours, freightRupees, SAMPLE_TRUCK } from './freight'
import freightTable from '../../../backend/src/main/resources/advisory/freight-table.sample.json'
import { BOUNDS, indiaDots, insideBounds, insidePolygon, MAP_HEIGHT, MAP_WIDTH, project, quadPoint, splitQuad } from './geo'
import { LANGUAGES, languageColour, type LanguageCode } from './languages'
import { clockAt, formatClock, locate, nextStepTime, statusOf, stepStart, threadLanguages, totalSeconds, whisperOn } from './playback'
import { SCENARIOS, STAGES, STEP_ORDER } from './scenarios'

const stageIndex = (id: string) => STAGES.findIndex((s) => s.id === id)

describe('stage order', () => {
  it('lists the eight stages in the order a deal happens', () => {
    expect(STAGES.map((s) => s.id)).toEqual(['call', 'deal', 'dispatch', 'storage', 'pickup', 'transit', 'arrival', 'payment'])
  })

  it('keeps every step in stage order and covers every stage, from the first ring to payment', () => {
    const order = STEP_ORDER.map((s) => stageIndex(s.stage))
    expect(order).toEqual([...order].sort((a, b) => a - b))
    expect(new Set(order).size).toBe(STAGES.length)
    expect(STEP_ORDER[0].id).toBe('ring')
    expect(STEP_ORDER[STEP_ORDER.length - 1].id).toBe('payment')
  })
})

describe('playback', () => {
  const steps = SCENARIOS[0].steps

  it('locates the playhead in the right step, and past the end every step is done', () => {
    expect(locate(steps, 0)).toEqual({ index: 0, fraction: 0 })
    expect(locate(steps, stepStart(steps, 3) + steps[3].seconds / 2)).toEqual({ index: 3, fraction: 0.5 })
    expect(locate(steps, totalSeconds(steps)).index).toBe(steps.length)
    expect(statusOf(2, { index: 3, fraction: 0 })).toBe('done')
    expect(statusOf(3, { index: 3, fraction: 0 })).toBe('active')
    expect(statusOf(4, { index: 3, fraction: 0 })).toBe('upcoming')
  })

  it('steps one step at a time from the first ring to the payment, then stops at the end', () => {
    let t = 0
    const seen: string[] = []
    while (t < totalSeconds(steps)) {
      seen.push(steps[locate(steps, t).index].id)
      t = nextStepTime(steps, t)
    }
    expect(seen).toEqual(STEP_ORDER.map((s) => s.id))
    expect(nextStepTime(steps, t)).toBe(totalSeconds(steps))
  })

  it('keeps the scenario clock moving forward and formats it as day and time', () => {
    for (const scenario of SCENARIOS) {
      const times = scenario.steps.map((s) => s.atMin)
      expect(times).toEqual([...times].sort((a, b) => a - b))
      const transit = scenario.steps.findIndex((s) => s.id === 'transit')
      const mid = clockAt(scenario, { index: transit, fraction: 0.5 })
      expect(mid).toBeGreaterThan(scenario.steps[transit].atMin)
      expect(mid).toBeLessThan(scenario.steps[transit + 1].atMin)
      expect(scenario.steps[transit + 1].atMin - scenario.steps[transit].atMin).toBe(scenario.etaHours * 60)
    }
    expect(formatClock(9 * 60)).toBe('Day 1, 09:00')
    expect(formatClock(24 * 60 + 16 * 60 + 5)).toBe('Day 2, 16:05')
  })

  it('switches the language on one half of the thread, and shows the halo only at the private beats on the farmer side', () => {
    const scenario = SCENARIOS[0]
    const at = (id: string, fraction = 0.5) => ({ index: scenario.steps.findIndex((s) => s.id === id), fraction })
    expect(threadLanguages(scenario, at('connect'))).toEqual({ farmer: 'kn', buyer: 'bn' })
    expect(threadLanguages(scenario, at('switch', 0.1))).toEqual({ farmer: 'kn', buyer: 'bn' })
    expect(threadLanguages(scenario, at('switch', 0.6))).toEqual({ farmer: 'kn', buyer: 'hi' })
    const whispering = scenario.steps.filter((_, i) => whisperOn(scenario, { index: i, fraction: 0.5 })).map((s) => s.id)
    expect(whispering).toEqual(['price-ref', 'caution'])
  })
})

describe('map geometry', () => {
  it('projects the bounds to the corners of the map box', () => {
    expect(project([BOUNDS.west, BOUNDS.north])).toEqual({ x: 0, y: 0 })
    const se = project([BOUNDS.east, BOUNDS.south])
    expect(se.x).toBeCloseTo(MAP_WIDTH)
    expect(se.y).toBeCloseTo(MAP_HEIGHT)
  })

  it('puts the real towns inside India, and the dot grid inside the map box', () => {
    const towns = { Gadag: [75.63, 15.43], Kolkata: [88.36, 22.57], Nashik: [73.79, 20.0], Chennai: [80.27, 13.08], Delhi: [77.21, 28.61] } as const
    for (const at of Object.values(towns)) {
      expect(insideBounds(at)).toBe(true)
      expect(insidePolygon(at)).toBe(true)
    }
    expect(insidePolygon([65, 20])).toBe(false)
    expect(insidePolygon([90.4, 23.8])).toBe(false) // Dhaka, outside the outline
    const dots = indiaDots()
    expect(dots.length).toBeGreaterThan(500)
    for (const d of dots) {
      expect(d.x).toBeGreaterThanOrEqual(0)
      expect(d.x).toBeLessThanOrEqual(MAP_WIDTH)
      expect(d.y).toBeGreaterThanOrEqual(0)
      expect(d.y).toBeLessThanOrEqual(MAP_HEIGHT)
    }
  })

  it('interpolates the route from one end to the other and splits the thread at its midpoint', () => {
    const a = { x: 0, y: 0 }
    const c = { x: 50, y: -40 }
    const b = { x: 100, y: 0 }
    expect(quadPoint(a, c, b, 0)).toEqual(a)
    expect(quadPoint(a, c, b, 1)).toEqual(b)
    expect(quadPoint(a, c, b, 0.5)).toEqual({ x: 50, y: -20 })
    const [first, second] = splitQuad(a, c, b)
    expect(first[2]).toEqual(quadPoint(a, c, b, 0.5))
    expect(second[0]).toEqual(first[2])
    expect(quadPoint(...first, 0.5).x).toBeCloseTo(quadPoint(a, c, b, 0.25).x)
    expect(quadPoint(...second, 0.5).y).toBeCloseTo(quadPoint(a, c, b, 0.75).y)
  })
})

describe('language colours', () => {
  it('gives every language a name and a distinct colour in each theme', () => {
    for (const theme of ['light', 'dark'] as const) {
      const colours = (Object.keys(LANGUAGES) as LanguageCode[]).map((code) => languageColour(code, theme))
      expect(new Set(colours).size).toBe(colours.length)
      for (const c of colours) expect(c).toMatch(/^#[0-9a-f]{6}$/)
    }
    expect(LANGUAGES.kn.name).toBe('Kannada')
    expect(LANGUAGES.bn.name).toBe('Bengali')
  })
})

describe('scenario data', () => {
  it('has at least two scenarios with different language pairs and crops', () => {
    expect(SCENARIOS.length).toBeGreaterThanOrEqual(2)
    expect(new Set(SCENARIOS.map((s) => s.deal.crop)).size).toBe(SCENARIOS.length)
    expect(new Set(SCENARIOS.map((s) => `${s.farmer.language}-${s.buyer.language}`)).size).toBe(SCENARIOS.length)
  })

  it('matches the voiced demo in the default scenario', () => {
    const [first] = SCENARIOS
    expect(first.farmer).toMatchObject({ person: 'Basavaraj', language: 'kn' })
    expect(first.buyer).toMatchObject({ person: 'Mondal Traders', language: 'bn', name: 'Kolkata' })
    expect(first.deal).toMatchObject({ crop: 'Onion', quantityQuintals: 40, pricePerQuintal: 1900, total: 76_000 })
  })

  it('keeps every scenario whole: all steps, every language known, pins inside India, totals and freight calculated', () => {
    for (const s of SCENARIOS) {
      expect(s.steps.map((step) => step.id)).toEqual(STEP_ORDER.map((step) => step.id))
      for (const step of s.steps) {
        expect(step.line.length).toBeGreaterThan(10)
        expect(step.who.length).toBeGreaterThan(2)
        for (const code of step.langs) expect(LANGUAGES[code]).toBeDefined()
      }
      for (const at of [s.farmer.at, s.buyer.at, s.transporter.at, s.storage.at, s.weighbridge.at]) expect(insidePolygon(at)).toBe(true)
      expect(s.deal.total).toBe(s.deal.quantityQuintals * s.deal.pricePerQuintal)
      expect(s.freight).toBe(freightRupees(s.km, s.deal.quantityQuintals))
      expect(s.etaHours).toBe(etaHours(s.km))
      expect(s.languageSwitch.to).not.toBe(s.languageSwitch.side === 'buyer' ? s.buyer.language : s.farmer.language)
    }
  })

  it('copies the truck rate from the sample freight table', () => {
    const truck = freightTable.vehicles.find((v) => v.id === 'truck')!
    expect(freightTable.label).toBe('synthetic sample')
    expect(SAMPLE_TRUCK).toMatchObject({ capacityQuintals: truck.capacityQuintals, rupeesPerKm: truck.rupeesPerKm, minimumTrip: truck.minimumTrip })
    expect(freightRupees(100, 100)).toBe(5500)
    expect(freightRupees(10, 10)).toBe(2500)
  })

  it('says nothing it cannot back: no real-time, instant, verified or scam-blocking claims, no e-NAM', () => {
    const copy = JSON.stringify(SCENARIOS)
    expect(copy).not.toMatch(/real[- ]time|instant|verified|blocks? scams?|money saved|e-?NAM|guarantee/i)
  })
})

describe('Deal map page', () => {
  const html = renderToStaticMarkup(<DealMapPage />)
  const text = html.replace(/<[^>]+>/g, ' ').replace(/&#x27;/g, "'").replace(/&amp;/g, '&').replace(/\s+/g, ' ')

  it('shows the simulated banner', () => {
    expect(text).toContain(SIMULATED_BANNER)
    expect(SIMULATED_BANNER).toContain('guarantees no truck, price or payment')
  })

  it('names every stage and every step of the default scenario', () => {
    for (const stage of STAGES) expect(text).toContain(stage.name)
    for (const step of SCENARIOS[0].steps) expect(text).toContain(step.title)
  })

  it('has the controls, the scenario switcher, the language legend and the hand-off line', () => {
    for (const label of [/Play|Pause/, 'Step', 'Restart', '1x', '2x', '4x', 'Kannada', 'Bengali', 'Hindi']) expect(text).toMatch(label)
    expect(html).toContain('aria-label="Scenario"')
    for (const s of SCENARIOS) expect(text).toContain(s.label)
    expect(text).toContain('weighment and grading happen at the mandi, not by G-One')
    expect(text).toMatch(/simulated/i)
    expect(text).not.toMatch(/real[- ]time|instant|verified|blocks scams|money saved|e-?NAM/i)
  })
})
