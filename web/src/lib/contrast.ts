/** WCAG 2.x contrast for the theme tokens in index.css. Used by the contrast test, not at runtime. */

export type Tokens = Record<string, string>

/** Reads `--name: #hex;` declarations from the first block that starts with `selector {`. */
export function readTokens(css: string, selector: string): Tokens {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) throw new Error(`No ${selector} block`)
  const body = css.slice(start, css.indexOf('}', start))
  const tokens: Tokens = {}
  for (const m of body.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) tokens[m[1]] = m[2].toLowerCase()
  return tokens
}

function channel(v: number): number {
  const c = v / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

export function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16)
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255)
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Foreground token, background token. Body text needs 4.5:1. */
export const TEXT_PAIRS: [string, string][] = [
  ['ink', 'page'], ['ink', 'surface'], ['ink', 'sunk'], ['ink', 'raised'],
  ['muted', 'page'], ['muted', 'surface'], ['muted', 'sunk'], ['muted', 'raised'],
  ['faint', 'page'], ['faint', 'surface'], ['faint', 'sunk'],
  ['brand', 'page'], ['brand', 'surface'], ['brand', 'brand-soft'],
  ['on-brand', 'brand'], ['on-brand', 'brand-hover'],
  ['on-accent', 'accent'], ['on-accent', 'accent-hover'], ['accent-ink', 'accent-soft'], ['accent-ink', 'page'],
  ['farmer', 'farmer-soft'], ['farmer', 'surface'],
  ['buyer', 'buyer-soft'], ['buyer', 'surface'],
  ['assistant', 'assistant-soft'], ['assistant', 'surface'],
  ['whisper', 'whisper-soft'], ['whisper', 'surface'],
  ['ok', 'ok-soft'], ['warn', 'warn-soft'], ['bad', 'bad-soft'], ['bad', 'surface'], ['on-bad', 'bad'],
  ['on-night', 'night'], ['on-night', 'night-raised'], ['on-night-muted', 'night'], ['on-night-muted', 'night-raised'], ['accent', 'night'],
]

/** Borders, focus rings and other non-text marks need 3:1 against what they sit on. */
export const UI_PAIRS: [string, string][] = [
  ['line-strong', 'page'], ['line-strong', 'surface'],
  ['focus', 'page'], ['focus', 'surface'], ['focus', 'sunk'],
  ['accent', 'night'], ['brand', 'page'],
]
