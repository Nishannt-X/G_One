import { describe, expect, it } from 'bun:test'
import manifest from '../../../public/demo/voiced-sample/manifest.json'
import { parseReference } from '../../lib/pricing'
import type { Manifest } from './manifest'
import { phoneLit } from './sides'
import { buildPlan, callViewAt, cursorAt, nextBeatStart } from './timeline'

const sample = manifest as unknown as Manifest
const plan = buildPlan(sample)

describe('voiced sample timeline', () => {
  it('places every clip in order with pauses between them', () => {
    expect(plan.segments.length).toBe(sample.beats.flatMap((b) => b.clips).length)
    for (let i = 1; i < plan.segments.length; i++) {
      expect(plan.segments[i].start).toBeGreaterThan(plan.segments[i - 1].end)
    }
    expect(plan.total).toBeCloseTo(plan.segments.at(-1)!.end)
  })

  it('finds the current clip, and holds the last line through a pause', () => {
    const second = plan.segments[1]
    expect(cursorAt(plan, second.start + 0.01)).toMatchObject({ beat: second.beat, clip: second.clip, voicing: true })
    expect(cursorAt(plan, second.end + 0.01)).toMatchObject({ beat: second.beat, clip: second.clip, voicing: false })
    expect(cursorAt(plan, -1).beat).toBe(-1)
  })

  it('skips to the next beat and ends after the last', () => {
    expect(nextBeatStart(plan, plan.beatStarts[3])).toBe(plan.beatStarts[4])
    expect(nextBeatStart(plan, plan.beatStarts.at(-1)! + 0.1)).toBe(plan.total)
  })

  it('lets every party hear only what the script says', () => {
    for (const beat of sample.beats) {
      for (const clip of beat.clips) {
        if (clip.kind === 'heard') expect(clip.hearing[clip.to!]).toBe('hears')
        if (beat.whisperKind && beat.whisperKind !== 'read_back') {
          expect(clip.hearing.farmer).toBe('hears')
          expect(clip.hearing.buyer).toBe('silent')
          expect(clip.pan).toBe(-1)
        }
      }
    }
    const scam = sample.beats.find((b) => b.notPassedOn)!
    expect(scam.clips.some((c) => c.kind === 'heard')).toBe(false)
    expect(scam.clips[0].hearing.farmer).toBe('silent')
  })

  it('builds the deal up beat by beat, and saves the memory note only at the end', () => {
    const last = sample.beats.length - 1
    const final = callViewAt(sample, last, 1_000, true)
    expect(final.deal).toMatchObject({ state: 'agreed', quantity: '40 quintal', price: 1900, unit: 'quintal', farmerYes: true, buyerYes: true })
    expect(final.memory?.gists).toHaveLength(2)
    expect(final.ended).not.toBeNull()
    const early = callViewAt(sample, 1, 1_000, false)
    expect(early.deal).toMatchObject({ state: 'draft', price: null, farmerYes: false })
    expect(early.memory?.gists).toHaveLength(1)
    expect(callViewAt(sample, -1, 1_000, false).memory).toBeNull()
  })

  it('says the same amounts in every language', () => {
    const WORDS: Record<string, string[]> = {
      '1,500': ['ಒಂದು ಸಾವಿರದ ಐನೂರು', 'पंद्रह सौ', 'পনেরোশো'],
      '1,800': ['ಒಂದು ಸಾವಿರದ ಎಂಟು ನೂರು', 'अठारह सौ'],
      '1,900': ['ಒಂದು ಸಾವಿರದ ಒಂಬೈನೂರು', 'उन्नीस सौ', 'উনিশশো'],
      '2,000': ['ಎರಡು ಸಾವಿರ', 'दो हज़ार'],
      '76,000': ['ಎಪ್ಪತ್ತಾರು ಸಾವಿರ', 'ছিয়াত্তর হাজার'],
    }
    let checked = 0
    for (const clip of sample.beats.flatMap((b) => b.clips)) {
      for (const [, amount] of (clip.gloss ?? '').matchAll(/₹(\d[\d,]*\d)/g)) {
        expect(WORDS[amount].some((word) => clip.text.includes(word))).toBe(true)
        checked += 1
      }
      expect(clip.text).not.toMatch(/\d/)
    }
    expect(checked).toBeGreaterThan(8)
    expect(40 * 1900).toBe(76_000)
  })

  it('delivers the summary to each phone in that side\'s language, with the same numbers', () => {
    const farmerBeat = sample.beats.findIndex((b) => b.deliver === 'farmer')
    const buyerBeat = sample.beats.findIndex((b) => b.deliver === 'buyer')
    expect(farmerBeat).toBeLessThan(buyerBeat)
    expect(sample.beats[buyerBeat].endsCall).toBe(true)
    expect(phoneLit(sample, { beat: farmerBeat - 1, clip: 0, voicing: true }, 'farmer', false)).toBe(false)
    expect(phoneLit(sample, { beat: farmerBeat, clip: 0, voicing: true }, 'farmer', false)).toBe(true)
    expect(phoneLit(sample, { beat: farmerBeat, clip: 0, voicing: true }, 'buyer', false)).toBe(false)
    expect(phoneLit(sample, { beat: -1, clip: -1, voicing: false }, 'buyer', true)).toBe(true)
    for (const party of ['farmer', 'buyer'] as const) {
      const values = sample.phones[party].rows.map((r) => r.value).join(' ')
      for (const expected of ['40', '₹1,900', '₹76,000', '2026-11-07', 'GO-4F7A']) expect(values).toContain(expected)
    }
    expect(sample.phones.farmer.subtitle).toMatch(/[\u0C80-\u0CFF]/)
    expect(sample.phones.buyer.subtitle).toMatch(/[\u0980-\u09FF]/)
    expect(sample.phones.buyer.rows[0].label).toMatch(/[\u0900-\u097F]/)
    expect(sample.disclaimer).toBe('Recorded by G-One from the call. Not a contract.')
  })

  it('states a reference the price meter can read', () => {
    const tool = sample.beats.find((b) => b.tool?.tool === 'mandi_price')!.tool!
    expect(parseReference(tool.result)).toMatchObject({ perQuintal: 1800, market: 'Hubballi', date: '2026-11-06' })
  })
})
