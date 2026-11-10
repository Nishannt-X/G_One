import { useCallback, useEffect, useRef, useState, type Dispatch } from 'react'
import { api } from '../lib/api'
import type { FeedAction } from '../lib/callState'

/** Opens the call page and plays the synthetic call there. */
export const SYNTHETIC_CALL_HREF = '#/call?synthetic=1'

/**
 * A synthetic text call with no speech: the backend replays a scripted farmer and buyer through its pipeline, or, when the
 * replay switch is off or the backend is away, the page plays the same sale locally. Not the recorded voiced sample.
 */
export function useSyntheticCall(dispatch: Dispatch<FeedAction>) {
  const [starting, setStarting] = useState(false)
  const stopMock = useRef<(() => void) | null>(null)

  const play = useCallback(async () => {
    setStarting(true)
    stopMock.current?.()
    try {
      await api('/api/dev/console/replay?speed=1', { method: 'POST' })
    } catch {
      const { playMock } = await import('../dev/mock')
      stopMock.current = playMock((event) => dispatch({ type: 'event', event, mock: true }), 1)
    } finally {
      setStarting(false)
    }
  }, [dispatch])

  useEffect(() => () => stopMock.current?.(), [])

  return { play, starting }
}
