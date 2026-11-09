import type { LegRole } from '../../types/console'

export type BeatKind = 'consent' | 'price_reference' | 'caution' | 'read_back'
/** speaking: their own words are playing. hears: this is what reaches their phone. silent: nothing reaches them. */
export type Hearing = 'speaking' | 'hears' | 'silent'

export interface Clip {
  id: string
  /** original: the person's own words. heard: G-One's translation. said: G-One's own words. */
  kind: 'original' | 'heard' | 'said'
  language: string
  /** Every language heard in this line, for labels. */
  languages: string[]
  text: string
  /** The Timbre text when it differs from what is shown; null otherwise. */
  spoken: string | null
  /** English for the viewer; null when the line is already English. */
  gloss: string | null
  to: LegRole | null
  voice: string
  file: string
  durationMs: number
  /** Stereo position: farmer left, buyer right. */
  pan: number
  hearing: Record<LegRole, Hearing>
  pauseAfterMs: number
}

export interface Beat {
  id: string
  speakerRole: LegRole | 'ai'
  audience: LegRole | 'both' | 'none'
  whisperKind: BeatKind | null
  notPassedOn: boolean
  buyerJoins: boolean
  endsCall: boolean
  /** This side's phone receives the deal summary as the beat starts. */
  deliver: LegRole | null
  tool: { tool: string; args: Record<string, unknown>; result: string } | null
  note: string | null
  dealPatch: Record<string, unknown> | null
  clips: Clip[]
}

export interface PhoneMessage {
  lang: string
  labelLang: string
  lockTime: string
  lockDate: string
  notice: string
  title: string
  subtitle: string
  rows: { label: string; value: string }[]
  note: string
  noteGloss: string
}

export interface Manifest {
  version: number
  label: string
  sampleRate: number
  farmer: { name: string; language: string; languages: string[] }
  buyer: { name: string; language: string; languages: string[] }
  phones: Record<LegRole, PhoneMessage>
  disclaimer: string
  memoryBefore: { facts: { field: string; value: string }[]; gist: { daysAgo: number; summary: string } }
  memoryAfter: { summary: string }
  totalMs: number
  beats: Beat[]
}

export const MANIFEST_BASE = '/demo/voiced-sample/'
