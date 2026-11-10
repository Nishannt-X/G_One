import { describe, expect, it } from 'bun:test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { contrast, readTokens, TEXT_PAIRS, UI_PAIRS, type Tokens } from './contrast'

const css = readFileSync(join(import.meta.dir, '..', 'index.css'), 'utf8')

/** The pairs below `min`, with their ratios, so a failure names every offender at once. */
function below(tokens: Tokens, pairs: [string, string][], min: number): string[] {
  return pairs.map(([fg, bg]) => [fg, bg, contrast(tokens[fg], tokens[bg])] as const).filter(([, , r]) => r < min).map(([fg, bg, r]) => `${fg} on ${bg}: ${r.toFixed(2)}`)
}

describe.each([[':root'], ['.dark']])('theme %s', (selector) => {
  const tokens = readTokens(css, selector)

  it('defines every token the checks name', () => {
    const names = new Set([...TEXT_PAIRS, ...UI_PAIRS].flat())
    expect([...names].filter((n) => !tokens[n])).toEqual([])
  })

  it('gives every text pair at least 4.5:1 (WCAG AA)', () => {
    expect(below(tokens, TEXT_PAIRS, 4.5)).toEqual([])
  })

  it('gives borders and focus rings at least 3:1', () => {
    expect(below(tokens, UI_PAIRS, 3)).toEqual([])
  })
})

it('matches the WCAG reference values', () => {
  expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5)
  expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2)
})
