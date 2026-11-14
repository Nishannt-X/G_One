import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { nextStepTime, totalSeconds } from './playback'

export type Speed = 1 | 2 | 4

const REDUCED = '(prefers-reduced-motion: reduce)'

function subscribeMotion(onChange: () => void) {
  const media = window.matchMedia(REDUCED)
  media.addEventListener('change', onChange)
  return () => media.removeEventListener('change', onChange)
}

/** True when the person asked for less motion: the map then steps through without moving anything. */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribeMotion, () => window.matchMedia(REDUCED).matches, () => false)
}

/** A playhead in seconds over the steps, driven by requestAnimationFrame while playing. */
export function useDealPlayer(steps: readonly { seconds: number }[], autoplay: boolean) {
  const total = totalSeconds(steps)
  const [t, setT] = useState(0)
  const [playing, setPlaying] = useState(autoplay)
  const [speed, setSpeed] = useState<Speed>(1)
  const tRef = useRef(0)

  const seek = useCallback((value: number) => {
    tRef.current = value
    setT(value)
  }, [])

  useEffect(() => {
    if (!playing) return
    let raf = 0
    let last = performance.now()
    const tick = (now: number) => {
      // A background tab pauses frames; a long gap must not skip whole steps.
      const dt = Math.min(0.25, (now - last) / 1000)
      last = now
      const next = Math.min(total, tRef.current + dt * speed)
      tRef.current = next
      setT(next)
      if (next >= total) {
        setPlaying(false)
        return
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [playing, speed, total])

  const play = useCallback(() => {
    if (tRef.current >= total) seek(0)
    setPlaying(true)
  }, [seek, total])
  const pause = useCallback(() => setPlaying(false), [])
  const step = useCallback(() => {
    setPlaying(false)
    seek(nextStepTime(steps, tRef.current))
  }, [seek, steps])
  const restart = useCallback(() => {
    seek(0)
    setPlaying(true)
  }, [seek])

  return { t, total, playing, speed, setSpeed, play, pause, step, restart, ended: t >= total }
}
