import { useEffect, useState } from 'react'
import { isSimulatorConfig, type Role, type SimulatorConfig } from '../simulator/protocol'

export interface DemoLine {
  role: Role
  text: string
  note?: string
}

/** The synthetic callers and languages on offer. Null until they arrive; `failed` when the backend did not answer. */
export function useSimulatorConfig(): { config: SimulatorConfig | null; failed: boolean } {
  const [config, setConfig] = useState<SimulatorConfig | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let cancelled = false
    fetch('/api/simulator/config')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: unknown) => {
        if (cancelled) return
        if (isSimulatorConfig(body)) setConfig(body)
        else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [])
  return { config, failed }
}

/** The offline demo's lines, offered as one-tap turns. Empty when the backend has none. */
export function useDemoLines(): DemoLine[] {
  const [lines, setLines] = useState<DemoLine[]>([])
  useEffect(() => {
    let cancelled = false
    fetch('/api/demo/script')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: unknown) => {
        const list = (body as { lines?: unknown } | null)?.lines
        if (!cancelled && Array.isArray(list)) setLines(list as DemoLine[])
      })
      .catch(() => {
        // No script on this backend: the turn box still works.
      })
    return () => {
      cancelled = true
    }
  }, [])
  return lines
}

/** "Caller A, 98765 43210" becomes "Caller A · ••210": a synthetic number, still not shown in full on screen. */
export function maskedCallerLabel(label: string): string {
  const [name, number] = label.split(',').map((s) => s.trim())
  if (!number) return label
  const digits = number.replace(/\D/g, '')
  return `${name} · ••${digits.slice(-3)}`
}
