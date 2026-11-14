/**
 * Shows any stored text in English letters: a small, deterministic transliteration of the Indian scripts (Devanagari,
 * Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam) into Latin. Mirrors the backend's
 * `memory/EnglishLetters.java`, which stores new names this way; this one covers names stored before it did.
 *
 * The Unicode blocks of these scripts share one layout (offset 0x15 is "ka" in each), so one table indexed by the
 * offset inside the block serves all of them. Hindi, Bengali, Punjabi and Gujarati drop the silent "a" at the end of a
 * word (Ramesh) and between sounded vowels (Kamla); the southern scripts write that vowel only when it is said.
 */

const VOWEL: (string | undefined)[] = new Array(0x80)
const CONSONANT: (string | undefined)[] = new Array(0x80)
;['a', 'a', 'a', 'i', 'i', 'u', 'u', 'ri', 'li', 'e', 'e', 'e', 'ai', 'o', 'o', 'o', 'au'].forEach((v, i) => (VOWEL[0x04 + i] = v))
;['a', 'i', 'i', 'u', 'u', 'ri', 'ri', 'e', 'e', 'e', 'ai', 'o', 'o', 'o', 'au'].forEach((v, i) => (VOWEL[0x3e + i] = v))
VOWEL[0x60] = 'ri'
VOWEL[0x61] = VOWEL[0x62] = VOWEL[0x63] = 'li'
;['k', 'kh', 'g', 'gh', 'ng', 'ch', 'chh', 'j', 'jh', 'n', 't', 'th', 'd', 'dh', 'n', 't', 'th', 'd', 'dh', 'n', 'n', 'p', 'ph', 'b', 'bh', 'm', 'y', 'r', 'r', 'l', 'l', 'zh', 'v', 'sh', 'sh', 's', 'h'].forEach((c, i) => (CONSONANT[0x15 + i] = c))
;['q', 'kh', 'g', 'z', 'r', 'rh', 'f', 'y'].forEach((c, i) => (CONSONANT[0x58 + i] = c))

const DEVANAGARI = 0x0900
const BENGALI = 0x0980
const GURMUKHI = 0x0a00
const GUJARATI = 0x0a80
const TAMIL = 0x0b80
const VIRAMA = 0x4d

interface Syllable {
  consonants: string
  count: number
  vowel: string
  inherent: boolean
  deleted: boolean
  coda: string
}

function blockOf(cp: number): number {
  return cp >= 0x0900 && cp <= 0x0d7f ? cp & ~0x7f : -1
}

const isJoiner = (cp: number) => cp === 0x200c || cp === 0x200d
const isVowelSign = (o: number) => (o >= 0x3e && o <= 0x4c) || o === 0x62 || o === 0x63
const dropsSilentA = (block: number) => block === DEVANAGARI || block === BENGALI || block === GURMUKHI || block === GUJARATI

function consonant(o: number, block: number, wordStart: boolean): string | undefined {
  if (o < 0 || o >= 0x80) return undefined
  if (block === BENGALI && o === 0x4e) return 't'
  if (block === BENGALI && o === 0x2c) return 'b'
  if (block === BENGALI && o === 0x2f) return wordStart ? 'j' : 'y'
  if (block === TAMIL && o === 0x1a) return wordStart ? 's' : 'ch'
  if (block === TAMIL && o === 0x1f) return 't'
  if (block === TAMIL && o === 0x31) return 'r'
  return CONSONANT[o]
}

function nasal(points: number[], k: number, block: number): string {
  const o = k + 1 < points.length ? points[k + 1] - block : -1
  if (o >= 0x2a && o <= 0x2e) return 'm'
  return o === 0x39 ? 'ng' : 'n'
}

function word(points: number[], block: number): string {
  const syllables: Syllable[] = []
  let cur: Syllable | null = null
  let joining = false
  points.forEach((cp, k) => {
    const o = cp - block
    const c = consonant(o, block, k === 0)
    if (c !== undefined) {
      const cluster = joining && cur !== null
      if (!cluster || cur === null) {
        cur = { consonants: '', count: 0, vowel: '', inherent: false, deleted: false, coda: '' }
        syllables.push(cur)
      }
      cur.consonants += cluster && c === 'v' && dropsSilentA(block) ? 'w' : c
      cur.count++
      const khandaTa = block === BENGALI && o === 0x4e
      cur.vowel = khandaTa ? '' : 'a'
      cur.inherent = !khandaTa
      joining = false
    } else if (o === VIRAMA) {
      if (cur?.inherent) {
        cur.vowel = ''
        cur.inherent = false
        joining = true
      }
    } else if (isVowelSign(o)) {
      if (cur?.inherent) {
        cur.vowel = VOWEL[o] ?? ''
        cur.inherent = false
      }
      joining = false
    } else if (o >= 0 && VOWEL[o] !== undefined) {
      cur = { consonants: '', count: 0, vowel: VOWEL[o] ?? '', inherent: false, deleted: false, coda: '' }
      syllables.push(cur)
      joining = false
    } else if (o === 0x01 || o === 0x02 || o === 0x03 || (block === GURMUKHI && o === 0x70)) {
      if (cur) cur.coda += o === 0x03 ? 'h' : nasal(points, k, block)
      joining = false
    } else if (o >= 0x66 && o <= 0x6f) {
      cur = { consonants: '', count: 0, vowel: '', inherent: false, deleted: false, coda: String(o - 0x66) }
      syllables.push(cur)
      joining = false
    }
  })
  if (dropsSilentA(block)) dropSilentA(syllables)
  return syllables.map((s) => s.consonants + (s.deleted ? '' : s.vowel) + s.coda).join('')
}

const sounded = (s: Syllable) => s.vowel !== '' && !s.deleted
const silentA = (s: Syllable) => s.inherent && s.vowel === 'a' && s.count === 1 && s.coda === ''

function dropSilentA(s: Syllable[]) {
  const n = s.length
  if (n < 2) return
  if (silentA(s[n - 1])) s[n - 1].deleted = true
  for (let k = n - 2; k >= 1; k--) {
    if (silentA(s[k]) && s[k + 1].count > 0 && sounded(s[k + 1]) && sounded(s[k - 1])) s[k].deleted = true
  }
}

const capitalise = (w: string) => (w ? w[0].toUpperCase() + w.slice(1) : w)

/** Every Indian-script word in Latin letters, title case; accents dropped from Latin; anything else unchanged. */
export function toLatin(text: string): string {
  const points = Array.from(text, (ch) => ch.codePointAt(0) ?? 0)
  let out = ''
  let i = 0
  while (i < points.length) {
    const block = blockOf(points[i])
    if (block < 0) {
      out += String.fromCodePoint(points[i])
      i++
      continue
    }
    const run: number[] = []
    while (i < points.length && (blockOf(points[i]) === block || isJoiner(points[i]))) {
      if (!isJoiner(points[i])) run.push(points[i])
      i++
    }
    out += capitalise(word(run, block))
  }
  return out.normalize('NFD').replace(/\p{M}+/gu, '')
}

/** True when the text has a letter outside the Latin script. */
export function hasNonLatinLetters(text: string): boolean {
  return /[^\p{Script=Latin}\P{L}]/u.test(text)
}

/**
 * A name in English letters: "रमेश पाटिल" is "Ramesh Patil", "Asha" stays "Asha". Digits, symbols and scripts this
 * cannot read are removed; null when no English letter is left.
 */
export function englishName(text: string | null | undefined): string | null {
  if (!text) return null
  const flat = toLatin(text)
    .replace(/[^A-Za-z.'-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^[.'-]+|[.'-]+$/g, '')
    .trim()
  return /[A-Za-z]/.test(flat) ? flat : null
}

/** Any stored value in English letters (keeps digits and punctuation, e.g. "40 quintal"); null when nothing is left. */
export function englishText(text: string | null | undefined): string | null {
  if (!text) return null
  const latin = toLatin(text)
  const flat = (hasNonLatinLetters(latin) ? latin.replace(/[^\p{Script=Latin}\P{L}]+/gu, ' ') : latin).replace(/\s+/g, ' ').trim()
  return /[A-Za-z0-9]/.test(flat) ? flat : null
}

/** The first English letter of a name, upper case, for an avatar; null when there is none. */
export function initialOf(name: string | null | undefined): string | null {
  const m = englishName(name)?.match(/[A-Za-z]/)
  return m ? m[0].toUpperCase() : null
}
