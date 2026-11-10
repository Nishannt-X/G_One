import { describe, expect, test } from 'bun:test'
import type { MemoryGist, MemoryRecord } from '../lib/memory'
import { findSavedGist, savedGistText } from './savedGist'

const gist = (callId: string, startedMs: number, followUps: string[] = []): MemoryGist => ({ callId, startedMs, endedMs: startedMs, summary: `gist ${callId}`, followUps })
const record = (gists: MemoryGist[]): MemoryRecord => ({ callerKey: 'k', known: true, facts: [], gists })

describe('saved gist', () => {
  test('finds the gist for this call by id', () => {
    expect(findSavedGist(record([gist('c-new', 9_000), gist('c-old', 1)]), 'c-new', ['c-old'], 10_000)?.callId).toBe('c-new')
  })

  test('falls back to a gist that was not loaded at the start of the call', () => {
    expect(findSavedGist(record([gist('other-id', 10_500), gist('c-old', 1)]), 'c-new', ['c-old'], 10_000)?.callId).toBe('other-id')
  })

  test('the gists known at call start are not taken as saved', () => {
    expect(findSavedGist(record([gist('c-old', 1)]), 'c-new', ['c-old'], 10_000)).toBeNull()
  })

  test('the note says what was saved and what is still open', () => {
    expect(savedGistText({ state: 'saved', gist: gist('c', 1), openItems: ['find onion buyer'] })).toBe('Saved for next time: gist c · Open: find onion buyer')
    expect(savedGistText({ state: 'saving' })).toContain('Saving')
  })
})
