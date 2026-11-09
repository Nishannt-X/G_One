import { useMemo, useState } from 'react'
import { joinUrl } from '../lib/lan'
import { CheckIcon } from '../ui/icons'
import { qrPath } from './phoneJoin'

function Qr({ value }: { value: string }) {
  const { size, path } = useMemo(() => qrPath(value), [value])
  return (
    <svg viewBox={`0 0 ${size} ${size}`} shapeRendering="crispEdges" role="img" aria-label="QR code that opens the buyer page on a phone" data-testid="join-qr" className="aspect-square h-full shrink-0 rounded-lg bg-white">
      <path d={path} fill="#000" />
    </svg>
  )
}

/** Where the buyer cell of the demo goes once a call exists: scan, or copy the link when the scan fails. */
export default function PhoneJoinCard({ hosts, port, callId, onUseThisTab }: { hosts: string[]; port: number; callId: string; onUseThisTab: () => void }) {
  const [chosen, setChosen] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const host = chosen && hosts.includes(chosen) ? chosen : hosts[0]
  const url = joinUrl(host, port, callId)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // The link stays on screen to select by hand.
    }
  }

  return (
    <div className="flex h-full gap-3">
      <Qr value={url} />
      <div className="flex min-w-0 flex-1 flex-col justify-between gap-1">
        <p className="leading-tight font-semibold">Scan to join as the buyer on a phone</p>
        <p data-testid="join-url" className="line-clamp-2 font-mono text-xs break-all text-muted select-all">{url}</p>
        {hosts.length > 1 && (
          <label>
            <span className="sr-only">Address the phone can reach</span>
            <select className="field min-h-0 py-0.5 text-xs" value={host} onChange={(e) => setChosen(e.target.value)}>
              {hosts.map((h) => <option key={h} value={h}>{h}</option>)}
            </select>
          </label>
        )}
        <div className="flex items-center gap-2">
          <button type="button" className="btn-quiet btn-sm" onClick={() => void copy()}>{copied ? <><CheckIcon /> Copied</> : 'Copy link'}</button>
          <button type="button" className="truncate text-sm font-semibold text-muted underline" onClick={onUseThisTab}>Use this tab</button>
        </div>
      </div>
    </div>
  )
}
