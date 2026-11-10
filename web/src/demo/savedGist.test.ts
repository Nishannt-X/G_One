import { describe, expect, test } from 'bun:test'
import { ApiError } from '../lib/api'
import type { MemoryGist, MemoryRecord } from '../lib/memory'
import { findSavedGist, savedGistText, unconfirmedReason } from './savedGist'

const gist = (callId: string, startedMs: number, followUps: string[] = []): MemoryGist => ({ callId, startedMs, endedMs: startedMs, summary: `gist ${callId}`, followUps })
const record = (gists: MemoryGist[]): MemoryRecord => ({ callerKey: 'k', known: true, facts: [], gists })

describe('saved gist', () => {
  test('finds the gist for this call by id', () => {
    expect(findSavedGist(record([gist('c-new', 9_000), gist('c-old', 1)]), 'c-new')?.callId).toBe('c-new')
  })

  test("another call's newer gist is never taken as this call's", () => {
    expect(findSavedGist(record([gist('other-id', 10_500), gist('c-old', 1)]), 'c-new')).toBeNull()
  })

  test('an empty call id matches nothing', () => {
    expect(findSavedGist(record([gist('', 10_500)]), '')).toBeNull()
  })

  test('the note says what was saved and what is still open', () => {
    expect(savedGistText({ state: 'saved', gist: gist('c', 1), openItems: ['find onion buyer'] })).toBe('Saved for next time: gist c · Open: find onion buyer')
    expect(savedGistText({ state: 'saving' })).toContain('Saving')
  })

  test('a failed or slow check says the save is unconfirmed, never that nothing was saved', () => {
    for (const reason of ['unauthorized', 'backend', 'timeout'] as const) {
      const text = savedGistText({ state: 'unconfirmed', reason })
      expect(text).toStartWith("Couldn't confirm the summary was saved")
      expect(text).not.toContain('No summary')
    }
    expect(savedGistText({ state: 'unconfirmed', reason: 'unauthorized' })).toContain('access code')
  })

  test('an auth failure is told apart from other failures', () => {
    expect(unconfirmedReason(new ApiError('unauthorized'))).toBe('unauthorized')
    expect(unconfirmedReason(new ApiError('server'))).toBe('backend')
    expect(unconfirmedReason(new ApiError('unreachable'))).toBe('backend')
    expect(unconfirmedReason(new Error('x'))).toBe('backend')
  })
})
