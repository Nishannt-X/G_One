import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import manifest from '../../../public/demo/voiced-sample/manifest.json'
import type { VoicedState } from './engine'
import type { Manifest } from './manifest'
import VoicedTimeline from './VoicedTimeline'

const sample = manifest as unknown as Manifest
const at = (id: string) => sample.beats.findIndex((beat) => beat.id === id)
const renderAt = (beat: number) => {
  const state: VoicedState = {
    status: 'ready', loaded: 0, total: 0, manifest: sample, plan: null, active: true,
    playing: true, finished: false, needsTap: false, speed: 1, muted: false,
    cursor: { beat, clip: 0, voicing: true },
  }
  return renderToStaticMarkup(<VoicedTimeline manifest={sample} state={state} onReplayPhones={() => {}} />)
}

describe('voiced timeline memory', () => {
  it('reveals the earlier call only after identity is confirmed, including when seeking', () => {
    expect(renderAt(at('farmer-language-ask'))).not.toContain(sample.memoryBefore.gist.summary)
    expect(renderAt(at('name-check'))).not.toContain(sample.memoryBefore.gist.summary)
    expect(renderAt(at('name-yes'))).not.toContain(sample.memoryBefore.gist.summary)
    expect(renderAt(at('greet'))).toContain(sample.memoryBefore.gist.summary)
    expect(renderAt(at('sell'))).toContain(sample.memoryBefore.gist.summary)
  })
})
