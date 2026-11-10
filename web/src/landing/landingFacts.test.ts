import { describe, expect, it } from 'bun:test'
import { BUILT_FACTS, LANGUAGES, TEST_COUNTS } from './builtFacts'
import { IMPACT_FOOTNOTE, IMPACT_STATS } from './impactStats'

/** Words the landing page must never use about itself without a measurement behind them. */
const FORBIDDEN = /real[- ]time|instant|verified identity|guarantee|blocks? scams|all languages|farmers helped|scams prevented|money saved|\bms\b|millisecond|latency/i

describe('impact statistics', () => {
  it('are exactly the six researched figures, in order', () => {
    expect(IMPACT_STATS.map((s) => s.figure)).toEqual(['9.30 crore', '89.4%', '96.8%', '80.7% / 48.4%', '8.2 million+', '~972 / ~690 lakh'])
  })

  it('each cite a Government of India release on PIB over https', () => {
    for (const s of IMPACT_STATS) expect(new URL(s.source.url).hostname).toBe('www.pib.gov.in')
  })

  it('each name a year or period', () => {
    for (const s of IMPACT_STATS) expect(`${s.label} ${s.note}`).toMatch(/\b(19|20)\d\d\b/)
  })

  it('say the cyber figure is national, not about farmers, and the language counts are census-era', () => {
    const cyber = IMPACT_STATS.find((s) => s.id === 'cyber-complaints')!
    expect(cyber.note).toContain('not farmer-specific')
    expect(IMPACT_STATS.find((s) => s.id === 'languages')!.note).toContain('Census-era')
  })

  it('carry the footnote that they are context, not results', () => {
    expect(IMPACT_FOOTNOTE).toBe('Context about the problem, not results achieved by G-One. Survey figures are estimates.')
  })

  it('make no claim about outcomes', () => {
    for (const s of IMPACT_STATS) expect(`${s.label} ${s.note}`).not.toMatch(FORBIDDEN)
  })
})

describe('what is built', () => {
  it('states test counts with their date', () => {
    const tests = BUILT_FACTS.find((f) => f.icon === 'tests')!
    expect(tests.body).toContain(`${TEST_COUNTS.backend} backend and ${TEST_COUNTS.web} web tests, as of ${TEST_COUNTS.asOf}`)
  })

  it('separates live-tested languages from configured ones', () => {
    const body = BUILT_FACTS.find((f) => f.icon === 'languages')!.body
    expect(body).toContain('Hindi, English and Hinglish are exercised in live tests')
    expect(body).toContain('Bengali, Tamil and Kannada are configured and awaiting review by native speakers')
    expect(LANGUAGES.liveTested).not.toContain('Bengali')
  })

  it('makes no unmeasured claim', () => {
    for (const f of BUILT_FACTS) expect(`${f.title} ${f.body}`).not.toMatch(FORBIDDEN)
  })
})
