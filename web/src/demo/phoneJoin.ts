import { useEffect, useState } from 'react'
import { encode } from 'uqr'
import { isSafeHost } from '../lib/lan'

export type PhoneJoin =
  | { kind: 'waiting'; hint?: undefined }
  | { kind: 'ready'; hosts: string[]; port: number; hint?: undefined }
  | { kind: 'off'; hint: string }

const NEEDS_HTTPS = 'Phone join needs HTTPS: run bun run dev:https in web/.'
const NO_ADDRESS = 'No network address for a phone. Join the hotspot, or set G_ONE_LAN_HOST.'

/** Read from the dev server each time a call appears, so a hotspot joined since the page loaded is picked up. */
export function usePhoneJoin(callId: string, lanOverride: string | null): PhoneJoin {
  const [info, setInfo] = useState<{ https: boolean; port: number; hosts: string[] } | 'none' | null>(null)

  useEffect(() => {
    if (!callId) return
    let cancelled = false
    fetch('/__g-one/lan')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: { https?: unknown; port?: unknown; hosts?: unknown }) => {
        if (cancelled) return
        const valid = typeof body.https === 'boolean' && typeof body.port === 'number' && Array.isArray(body.hosts)
        setInfo(valid ? { https: body.https as boolean, port: body.port as number, hosts: (body.hosts as unknown[]).filter((h): h is string => typeof h === 'string') } : 'none')
      })
      .catch(() => {
        // A production build has no dev server to ask.
        if (!cancelled) setInfo('none')
      })
    return () => {
      cancelled = true
    }
  }, [callId])

  if (info === null) return { kind: 'waiting' }
  if (info === 'none' || !info.https) return { kind: 'off', hint: NEEDS_HTTPS }
  const override = lanOverride && isSafeHost(lanOverride.trim()) ? [lanOverride.trim()] : []
  const hosts = [...new Set([...override, ...info.hosts])]
  return hosts.length > 0 ? { kind: 'ready', hosts, port: info.port } : { kind: 'off', hint: NO_ADDRESS }
}

/** Dark modules as one SVG path. Black on white in either theme, because scanners need the contrast. */
export function qrPath(value: string): { size: number; path: string } {
  const { size, data } = encode(value, { ecc: 'L', border: 2 })
  let path = ''
  data.forEach((row, y) => row.forEach((dark, x) => { if (dark) path += `M${x} ${y}h1v1h-1z` }))
  return { size, path }
}
