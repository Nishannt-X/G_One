import { useEffect, useState } from 'react'

/** The current time, refreshed every `intervalMs` while `active`. Use it in a small leaf component, so only that re-renders. */
export function useNow(active: boolean, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    // Catch up at once: a timer that was paused (an ended call) would otherwise show a stale time for one interval.
    const catchUp = setTimeout(() => setNow(Date.now()), 0)
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => {
      clearTimeout(catchUp)
      clearInterval(timer)
    }
  }, [active, intervalMs])
  return now
}
