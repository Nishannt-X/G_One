import { useCallback, useState } from 'react'
import { api, ApiError, type ApiFailure } from './api'

/** Plays the backend's synthetic farmer and buyer deal. Needs no API keys, only the replay switch on the backend. */
export function useSampleCall(onStarted: () => void) {
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiFailure | null>(null)
  const play = useCallback(async () => {
    setBusy(true)
    setFailure(null)
    try {
      await api('/api/dev/console/replay?speed=1', { method: 'POST' })
      onStarted()
    } catch (error) {
      setFailure(error instanceof ApiError ? error.failure : 'server')
    } finally {
      setBusy(false)
    }
  }, [onStarted])
  return { play, busy, failure }
}
