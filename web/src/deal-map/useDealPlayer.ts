import { useCallback, useEffect, useReducer, useSyncExternalStore } from 'react'
import { INITIAL_PLAYER, reducePlayer, type PlayerAction, type PlayerState } from './playback'

const REDUCED = '(prefers-reduced-motion: reduce)'

function subscribeMotion(onChange: () => void) {
  const media = window.matchMedia(REDUCED)
  media.addEventListener('change', onChange)
  return () => media.removeEventListener('change', onChange)
}

/** True when the person asked for less motion: the map then jumps from step to step and never glides. */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribeMotion, () => window.matchMedia(REDUCED).matches, () => false)
}

/** The playhead over a demo deal's steps, driven by requestAnimationFrame while playing. */
export function useDealPlayer(steps: readonly { seconds: number }[]) {
  const [state, dispatch] = useReducer((s: PlayerState, a: PlayerAction) => reducePlayer(s, a, steps), INITIAL_PLAYER)

  useEffect(() => {
    if (!state.playing) return
    let raf = 0
    let last = performance.now()
    const tick = (now: number) => {
      dispatch({ type: 'tick', dt: (now - last) / 1000 })
      last = now
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [state.playing])

  const send = useCallback((action: PlayerAction) => dispatch(action), [])
  return { ...state, send }
}
