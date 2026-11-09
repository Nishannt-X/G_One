// Development only: LiveView loads this file behind `import.meta.env.DEV`, so production builds never contain it.
import { useEffect, useRef } from 'react'
import type { ConsoleEvent } from '../types/console'
import { playMock } from './mock'

/** Buttons that fill the console with synthetic calls, no backend needed. */
export default function DevTools({ emit }: { emit: (event: ConsoleEvent) => void }) {
  const stop = useRef<(() => void) | null>(null)

  useEffect(() => () => stop.current?.(), [])

  const mock = (speed: number) => {
    stop.current?.()
    stop.current = playMock(emit, speed)
  }

  return (
    <details className="rounded-xl border border-dashed border-line-strong p-3 text-sm text-muted">
      <summary className="cursor-pointer font-semibold">Dev tools (not in production builds, synthetic data only)</summary>
      <div className="mt-2 flex flex-wrap gap-2">
        <button type="button" className="btn-quiet" onClick={() => mock(1)}>Play mock call (25 s)</button>
        <button type="button" className="btn-quiet" onClick={() => mock(1000)}>Show finished mock call</button>
      </div>
    </details>
  )
}
