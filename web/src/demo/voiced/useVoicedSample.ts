import { useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { VoicedEngine } from './engine'
import { callViewAt } from './timeline'

/** The voiced sample: loads on arrival and plays once the viewer asks, or at once for ?autoplay=1. */
export function useVoicedSample(autoplay: boolean) {
  const [engine] = useState(() => new VoicedEngine())
  const [startedMs] = useState(() => Date.now())
  const state = useSyncExternalStore(engine.subscribe, engine.getState)

  useEffect(() => {
    void engine.load()
    return () => engine.dispose()
  }, [engine])

  useEffect(() => {
    if (autoplay && state.status === 'ready') void engine.autostart()
  }, [autoplay, engine, state.status])

  const { manifest, finished, active } = state
  const beat = state.cursor.beat
  const view = useMemo(() => (manifest && active ? callViewAt(manifest, beat, startedMs, finished) : null), [manifest, active, beat, startedMs, finished])
  return { engine, state, view }
}

export type VoicedSample = ReturnType<typeof useVoicedSample>
