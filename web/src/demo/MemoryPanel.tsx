import type { CallView } from '../lib/callState'
import { cropsOf, openFollowUps } from '../lib/farmers'
import { shortDate } from '../lib/format'
import { CheckIcon } from '../ui/icons'
import { savedGistText, type SavedGist } from './savedGist'

/** What G-One knew about the farmer when the call began (crops, open follow-ups, last call) and what it kept after. */
export default function MemoryPanel({ call, saved }: { call: CallView | null; saved: SavedGist | null }) {
  const memory = call?.memory ?? null
  const name = memory?.facts.find((f) => f.field === 'name')?.value ?? null
  const place = memory?.facts.find((f) => f.field === 'village' || f.field === 'district')?.value ?? null
  const crops = memory ? cropsOf(memory.facts) : []
  const open = memory ? openFollowUps(memory.gists) : []
  const last = memory?.gists.reduce<(typeof memory.gists)[number] | null>((a, g) => (!a || g.startedMs > a.startedMs ? g : a), null) ?? null
  return (
    <section aria-label="What G-One remembered" className="border-t border-line pt-4">
      <h3 className="font-semibold">What G-One remembered</h3>
      <div className="mt-1.5 min-h-[4.5rem] space-y-2 text-[0.9375rem] leading-snug">
        {!memory ? (
          <p className="text-muted">Loaded at the start of the call.</p>
        ) : !memory.returning && memory.facts.length === 0 ? (
          <p className="text-muted">First call from this number. Nothing on file yet.</p>
        ) : (
          <>
            {(name || place) && <p className="font-semibold">{[name, place].filter(Boolean).join(' · ')}</p>}
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-sm font-medium text-faint">Crops</span>
              {crops.length > 0 ? crops.map((c) => <span key={c} className="badge capitalize">{c}</span>) : <span className="text-muted">none saved yet</span>}
            </div>
            <div>
              <p className="text-sm font-medium text-faint">Open follow-ups</p>
              {open.length > 0 ? (
                <ul className="list-disc pl-5">{open.map((item) => <li key={item}>{item}</li>)}</ul>
              ) : <p className="text-muted">Nothing open</p>}
            </div>
            {last && <p key={last.callId} className="animate-fill text-muted"><span className="font-semibold text-ink">{shortDate(last.startedMs)}:</span> {last.summary}</p>}
          </>
        )}
        {saved && (
          <p role="status" className={`flex items-start gap-1.5 rounded-lg border px-3 py-2 font-medium ${saved.state === 'saved' ? 'border-ok/40 bg-ok-soft text-ok' : 'border-line text-muted'} ${saved.state === 'saving' ? 'animate-shimmer' : ''}`}>
            {saved.state === 'saved' && <span className="mt-0.5"><CheckIcon /></span>}<span>{savedGistText(saved)}</span>
          </p>
        )}
      </div>
    </section>
  )
}
