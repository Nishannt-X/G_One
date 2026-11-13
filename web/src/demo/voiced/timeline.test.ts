import { describe, expect, it } from 'bun:test'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import manifest from '../../../public/demo/voiced-sample/manifest.json'
import { parseReference } from '../../lib/pricing'
import type { Manifest } from './manifest'
import { phoneLit } from './sides'
import { buildPlan, callViewAt, cursorAt, nextBeatStart } from './timeline'

const sample = manifest as unknown as Manifest
const plan = buildPlan(sample)
const AUDIO = join(import.meta.dir, '..', '..', '..', 'public', 'demo', 'voiced-sample')
const LANGUAGE: Record<string, string> = { 'en-IN': 'English', 'kn-IN': 'Kannada', 'bn-IN': 'Bengali', 'hi-IN': 'Hindi' }

/** Length in milliseconds of a PCM WAV file, from its fmt and data chunks. */
function wavMs(bytes: Buffer): number {
  expect(bytes.toString('ascii', 0, 4)).toBe('RIFF')
  expect(bytes.toString('ascii', 8, 12)).toBe('WAVE')
  let bytesPerSecond = 0
  for (let at = 12; at + 8 <= bytes.length;) {
    const id = bytes.toString('ascii', at, at + 4)
    const size = bytes.readUInt32LE(at + 4)
    if (id === 'fmt ') bytesPerSecond = bytes.readUInt32LE(at + 16)
    if (id === 'data') return (Math.min(size, bytes.length - at - 8) / bytesPerSecond) * 1000
    at += 8 + size + (size & 1)
  }
  throw new Error('no data chunk')
}

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
    const scam = sample.beats.find((b) => b.id === 'qr-ask')!
    expect(scam.clips.some((c) => c.kind === 'heard')).toBe(false)
    expect(scam.clips[0].hearing.farmer).toBe('silent')
  })

  it('answers a question put to G-One only to the one who asked', () => {
    const asks = sample.beats.filter((b) => b.speakerRole === 'buyer' && b.clips[0].text.startsWith('জি-ওয়ান'))
    expect(asks.length).toBe(2)
    for (const ask of asks) {
      const answer = sample.beats[sample.beats.indexOf(ask) + 1]
      expect(ask.notPassedOn && answer.notPassedOn).toBe(true)
      expect(answer.speakerRole).toBe('ai')
      for (const clip of [...ask.clips, ...answer.clips]) expect(clip.hearing.farmer).toBe('silent')
      expect(answer.clips.every((c) => c.to === 'buyer' && c.language === 'bn-IN')).toBe(true)
    }
  })

  it('chooses each language before the greeting and asks to end and remember before goodbye', () => {
    const at = (id: string) => sample.beats.findIndex((b) => b.id === id)
    expect(sample.beats.slice(0, 6).map((b) => b.id)).toEqual(['farmer-language-ask', 'farmer-language-answer', 'farmer-language-confirm', 'name-check', 'name-yes', 'greet'])
    expect(sample.beats.slice(at('buyer-language-ask'), at('disclose') + 1).map((b) => b.id)).toEqual(['buyer-language-ask', 'buyer-language-answer', 'buyer-language-confirm', 'disclose'])
    expect(sample.beats[at('buyer-language-ask')].buyerJoins).toBe(true)
    expect(sample.beats[at('farmer-language-confirm')].clips[0].language).toBe('kn-IN')
    expect(sample.beats[at('buyer-language-confirm')].clips[0].language).toBe('bn-IN')
    expect(at('name-check')).toBeLessThan(at('greet'))
    expect(at('sent-buyer')).toBeLessThan(at('ask-end'))
    expect(at('ask-end')).toBeLessThan(at('ask-save'))
    expect(at('ask-save')).toBeLessThan(at('goodbye'))
    expect(sample.beats.filter((b) => b.endsCall).map((b) => b.id)).toEqual(['goodbye'])
  })

  it('builds the deal up beat by beat, and saves the memory note only at the end', () => {
    const last = sample.beats.length - 1
    const final = callViewAt(sample, last, 1_000, true)
    expect(final.deal).toMatchObject({ state: 'agreed', quantity: '40 quintal', price: 1900, unit: 'quintal', farmerYes: true, buyerYes: true })
    expect(final.memory?.gists).toHaveLength(2)
    expect(final.ended).not.toBeNull()
    const early = callViewAt(sample, sample.beats.findIndex((b) => b.id === 'sell'), 1_000, false)
    expect(early.deal).toMatchObject({ state: 'draft', price: null, farmerYes: false })
    expect(early.memory?.gists).toHaveLength(1)
    expect(callViewAt(sample, -1, 1_000, false).memory).toBeNull()
    const confirmed = sample.beats.findIndex((b) => b.id === 'name-yes')
    expect(callViewAt(sample, confirmed - 1, 1_000, false).memory).toBeNull()
    expect(callViewAt(sample, confirmed, 1_000, false).memory?.gists).toHaveLength(1)
    expect(callViewAt(sample, 0, 1_000, false).memory).toBeNull()
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
    expect(buyerBeat).toBeLessThan(sample.beats.length - 1)
    expect(sample.beats.at(-1)!.endsCall).toBe(true)
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

  it('asks each party in English first, then speaks the language they answer in', () => {
    const opening = (ask: string, answer: string, confirm: string, party: 'farmer' | 'buyer', language: string) => {
      const [question, reply, confirmation] = [ask, answer, confirm].map((id) => sample.beats.find((b) => b.id === id)!)
      expect(question.speakerRole).toBe('ai')
      expect(question.clips.map((c) => [c.language, c.to, c.gloss])).toEqual([['en-IN', party, null]])
      expect(question.clips[0].text).toContain('Which language would you like to speak?')
      expect(reply.speakerRole).toBe(party)
      expect(reply.clips.map((c) => c.language)).toEqual([language])
      expect(confirmation.speakerRole).toBe('ai')
      expect(confirmation.clips.map((c) => [c.language, c.to])).toEqual([[language, party]])
    }
    opening('farmer-language-ask', 'farmer-language-answer', 'farmer-language-confirm', 'farmer', 'kn-IN')
    opening('buyer-language-ask', 'buyer-language-answer', 'buyer-language-confirm', 'buyer', 'bn-IN')
    const buyerAsk = sample.beats.findIndex((b) => b.id === 'buyer-language-ask')
    for (const clip of sample.beats.slice(0, buyerAsk).flatMap((b) => b.clips)) expect(clip.hearing.buyer).toBe('silent')
    const named = sample.beats.findIndex((b) => b.id === 'name-yes')
    for (const clip of sample.beats.slice(2, named + 1).flatMap((b) => b.clips)) expect(clip.language).toBe('kn-IN')
  })

  it('glosses every line that is not English, and labels each line with its language', () => {
    for (const clip of sample.beats.flatMap((b) => b.clips)) {
      if (clip.language === 'en-IN') expect(clip.gloss).toBeNull()
      else expect(clip.gloss?.length).toBeGreaterThan(0)
      expect(clip.languages[0]).toBe(LANGUAGE[clip.language])
    }
  })

  it('plays committed audio files of the stated length, with none left over', () => {
    const clips = sample.beats.flatMap((b) => b.clips)
    for (const clip of clips) {
      expect(clip.file.startsWith(`${clip.id}-`)).toBe(true)
      const ms = wavMs(readFileSync(join(AUDIO, clip.file)))
      expect(ms).toBeGreaterThan(300)
      expect(Math.abs(ms - clip.durationMs)).toBeLessThan(2)
    }
    const files = readdirSync(AUDIO).filter((f) => f.endsWith('.wav')).sort()
    expect(files).toEqual(clips.map((c) => c.file).sort())
  })
})
