import { useEffect, useMemo, useState } from 'react'
import CallHeader from '../live/CallHeader'
import DealCard from '../live/DealCard'
import LatencyHud from '../live/LatencyHud'
import MemoryGlance from '../live/MemoryGlance'
import PriceMeter from '../live/PriceMeter'
import Stage from '../live/Stage'
import ToolsCard from '../live/ToolsCard'
import {
  callCode,
  CONSENT_LABEL,
  filterCalls,
  languagesOf,
  NO_FILTER,
  notStoredText,
  OUTCOME_LABEL,
  toCallView,
  type AnalyticsSummary,
  type CallFilter,
  type CallRecordView,
  type CallSummary,
  type Consent,
  type Outcome,
} from '../lib/analytics'
import { accessCode, api, ApiError, setAccessCode, type ApiFailure } from '../lib/api'
import { duration, languageLabel, millis, rupees } from '../lib/format'
import { ArrowIcon, LockIcon, TrashIcon } from '../ui/icons'
import { Card, Dialog, Empty, ErrorNote, Skeleton } from '../ui/parts'

const CONSENT_CLASS: Record<Consent, string> = {
  YES: 'bg-ok-soft text-ok',
  NO: 'bg-bad-soft text-bad',
  NONE: 'bg-sunk text-muted',
}

function ConsentChip({ who, consent }: { who: string; consent: Consent }) {
  return <span className={`chip ${CONSENT_CLASS[consent]}`}>{who}: {CONSENT_LABEL[consent]}</span>
}

function dateTime(ms: number): string {
  return new Date(ms).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

function pairLabel(languages: CallSummary['languages']): string {
  const farmer = languages.farmer ? languageLabel(languages.farmer) : null
  const buyer = languages.buyer ? languageLabel(languages.buyer) : null
  if (farmer && buyer) return `${farmer} ⇄ ${buyer}`
  return farmer ?? buyer ?? 'Not stored'
}

function dealLine(deal: CallSummary['deal']): string {
  if (!deal) return '—'
  const parts = [deal.commodity, deal.quantity, deal.price !== null ? `${rupees(deal.price)}${deal.unit ? `/${deal.unit}` : ''}` : null, deal.market]
  return parts.filter(Boolean).join(' · ') || '—'
}

function AccessForm({ onSaved }: { onSaved: () => void }) {
  const [code, setCode] = useState(accessCode())
  return (
    <form className="card flex flex-wrap items-center gap-2 p-4" onSubmit={(e) => { e.preventDefault(); setAccessCode(code); onSaved() }}>
      <span className="flex items-center gap-2 font-semibold"><LockIcon /> Stored calls need an access code</span>
      <label className="min-w-40 flex-1"><span className="sr-only">Access code</span><input type="password" className="field" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" /></label>
      <button type="submit" className="btn-primary" disabled={!code}>Unlock</button>
    </form>
  )
}

/** The figures strip. Every number comes from the stored calls only. */
export function KpiStrip({ summary }: { summary: AnalyticsSummary }) {
  const pairs = Object.entries(summary.languagePairs).sort((a, b) => b[1] - a[1])
  const tools = Object.entries(summary.toolCounts).sort((a, b) => b[1] - a[1])
  const tiles = [
    { label: 'Stored calls', value: String(summary.storedCalls) },
    { label: 'Both said yes to terms', value: String(summary.bothYesDeals) },
    { label: 'Average length', value: summary.storedCalls ? duration(summary.averageDurationMs) : '—' },
    { label: 'Median reply time (measured)', value: summary.medianMs.reply !== undefined ? millis(summary.medianMs.reply) : '—' },
  ]
  return (
    <section aria-label="Figures from stored calls" className="space-y-3">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles.map((tile) => (
          <div key={tile.label} className="card p-4">
            <dt className="text-sm font-semibold text-muted">{tile.label}</dt>
            <dd className="mt-1 font-mono text-2xl font-bold tabular-nums">{tile.value}</dd>
          </div>
        ))}
      </dl>
      {(pairs.length > 0 || tools.length > 0 || summary.medianMs.transcript !== undefined) && (
        <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted">
          {summary.medianMs.transcript !== undefined && <p>Speech to text, median: <span className="font-semibold text-ink tabular-nums">{millis(summary.medianMs.transcript)}</span></p>}
          {summary.medianMs.translation !== undefined && <p>Translation, median: <span className="font-semibold text-ink tabular-nums">{millis(summary.medianMs.translation)}</span></p>}
          {pairs.length > 0 && <p>Languages: {pairs.map(([pair, n]) => <span key={pair} className="font-semibold text-ink">{pair.split(' ⇄ ').map((l) => (l === 'unknown' ? 'Not stored' : languageLabel(l))).join(' ⇄ ')} ({n}) </span>)}</p>}
          {tools.length > 0 && <p>Tools: {tools.map(([tool, n]) => <span key={tool} className="font-mono font-semibold text-ink">{tool} ({n}) </span>)}</p>}
        </div>
      )}
      <p className="text-xs text-muted">Figures cover stored calls only. A call is stored only when a party says yes to remembering it.</p>
    </section>
  )
}

/** The stored calls table with filters and sort. */
export function CallsTable({ calls, filter, onFilter }: { calls: CallSummary[]; filter: CallFilter; onFilter: (f: CallFilter) => void }) {
  if (calls.length === 0) {
    return <Card><Empty title="No calls stored yet">A call is stored only when a party says yes to remembering it.</Empty></Card>
  }
  const shown = filterCalls(calls, filter)
  const languages = languagesOf(calls)
  return (
    <section aria-label="Stored calls" className="card overflow-hidden">
      <div className="flex flex-wrap items-end gap-3 border-b border-line p-3">
        <label className="min-w-36 flex-1 text-sm font-semibold">Language
          <select className="field mt-1" value={filter.language} onChange={(e) => onFilter({ ...filter, language: e.target.value })}>
            <option value="all">All languages</option>
            {languages.map((l) => <option key={l} value={l}>{languageLabel(l)}</option>)}
          </select>
        </label>
        <label className="min-w-36 flex-1 text-sm font-semibold">Outcome
          <select className="field mt-1" value={filter.outcome} onChange={(e) => onFilter({ ...filter, outcome: e.target.value as Outcome | 'all' })}>
            <option value="all">All outcomes</option>
            {(Object.keys(OUTCOME_LABEL) as Outcome[]).map((o) => <option key={o} value={o}>{OUTCOME_LABEL[o]}</option>)}
          </select>
        </label>
        <label className="min-w-36 flex-1 text-sm font-semibold">Sort
          <select className="field mt-1" value={filter.sort} onChange={(e) => onFilter({ ...filter, sort: e.target.value as CallFilter['sort'] })}>
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
            <option value="longest">Longest first</option>
          </select>
        </label>
      </div>
      {shown.length === 0 ? (
        <Empty title="No stored calls match">Change the language or outcome filter.</Empty>
      ) : (
        <ul className="divide-y divide-line">
          {shown.map((c) => (
            <li key={c.callId}>
              <a href={`#/analytics/calls?id=${encodeURIComponent(c.callId)}`} className="grid gap-x-4 gap-y-1 px-4 py-3 transition-colors hover:bg-sunk md:grid-cols-[7rem_9rem_5rem_minmax(0,1fr)_minmax(0,1.2fr)_auto] md:items-center">
                <span className="font-mono font-bold" title={c.callId}>{callCode(c.callId)}</span>
                <span className="text-sm text-muted">{dateTime(c.startedMs)}</span>
                <span className="font-mono text-sm tabular-nums">{duration(c.durationMs)}</span>
                <span className="truncate text-sm">{pairLabel(c.languages)}</span>
                <span className="truncate text-sm" title={dealLine(c.deal)}><span className="font-semibold">{OUTCOME_LABEL[c.outcome]}</span> · {dealLine(c.deal)}</span>
                <span className="flex flex-wrap gap-1.5">
                  <ConsentChip who="Farmer" consent={c.consentFarmer} />
                  <ConsentChip who="Buyer" consent={c.consentBuyer} />
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function NotStored({ title, text }: { title: string; text: string }) {
  return <Card title={title}><p className="text-muted">{text}</p></Card>
}

/** One stored call, on the live cards. No conversation and no private advice text: neither is ever stored. */
export function CallDetail({ record, onDelete }: { record: CallRecordView; onDelete?: () => void }) {
  const call = toCallView(record)
  const { farmer, buyer, notStored } = record.data
  const [confirm, setConfirm] = useState(false)
  const [copied, setCopied] = useState(false)
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <a href="#/analytics/calls" className="btn-quiet btn-sm"><ArrowIcon className="rotate-180" /> All stored calls</a>
        <h2 className="text-xl font-bold"><span className="font-mono">{callCode(record.callId)}</span></h2>
        <button type="button" className="font-mono text-sm text-faint underline-offset-4 hover:underline" title="Copy the full call id" onClick={() => { void navigator.clipboard?.writeText(record.callId).then(() => setCopied(true)) }}>
          {record.callId}{copied ? ' (copied)' : ''}
        </button>
        {onDelete && <button type="button" className="btn-danger btn-sm ml-auto" onClick={() => setConfirm(true)}><TrashIcon /> Delete record</button>}
      </div>
      <CallHeader call={call} calls={[call]} onPick={() => {}} />
      <div className="flex flex-wrap gap-2">
        <ConsentChip who="Farmer" consent={record.consentFarmer} />
        <ConsentChip who="Buyer" consent={record.consentBuyer} />
        <span className="chip bg-sunk text-muted">{OUTCOME_LABEL[record.outcome]}</span>
      </div>
      <p className="text-sm text-muted">The conversation itself is never stored: no subtitles, no recordings and no private advice text. Only the details below are kept.</p>
      {call.legs.length > 0 && <Stage call={call} />}
      {!farmer && <p role="note" className="rounded-xl bg-sunk px-4 py-2 text-muted">{notStoredText('farmer', notStored.farmer)}</p>}
      {!buyer && <p role="note" className="rounded-xl bg-sunk px-4 py-2 text-muted">{notStoredText('buyer', notStored.buyer)}</p>}
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <DealCard call={call} />
        <PriceMeter call={call} />
      </div>
      <div className="grid items-start gap-4 md:grid-cols-2 xl:grid-cols-3">
        {call.timings.length > 0 ? <LatencyHud call={call} /> : <NotStored title="Turn latency" text="No measured turns were stored for this call." />}
        {farmer ? <MemoryGlance call={call} /> : <NotStored title="Farmer memory" text={notStoredText('farmer', notStored.farmer)} />}
        {farmer ? <ToolsCard call={call} /> : <NotStored title="AI tools and lookups" text={notStoredText('farmer', notStored.farmer)} />}
      </div>
      {farmer && farmer.advice.length > 0 && (
        <Card title="Private advice given">
          <p className="text-muted">Only the kind and time are stored, never the words.</p>
          <ul className="mt-2 flex flex-wrap gap-2">
            {farmer.advice.map((a, i) => <li key={i} className="chip bg-whisper-soft text-whisper">{a.kind.replace('_', ' ')} · {duration(Math.max(0, a.atMs - record.startedMs))}</li>)}
          </ul>
        </Card>
      )}
      <Dialog open={confirm} onClose={() => setConfirm(false)} title="Delete this call record?">
        <p className="text-muted">The stored record of {callCode(record.callId)} is removed for good. Farmer memory is not changed.</p>
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={() => setConfirm(false)}>Cancel</button>
          <button type="button" className="btn-danger" onClick={() => { setConfirm(false); onDelete?.() }}><TrashIcon /> Delete</button>
        </div>
      </Dialog>
    </div>
  )
}

function failureText(failure: ApiFailure): string {
  return failure === 'unreachable' ? 'The backend did not answer. Start it and try again.' : 'The stored calls could not be loaded.'
}

/** The Calls tab: figures, the stored calls, or one call when `callId` is set. */
export default function CallsView({ callId }: { callId: string | null }) {
  const [calls, setCalls] = useState<CallSummary[] | null>(null)
  const [summary, setSummary] = useState<AnalyticsSummary | null>(null)
  const [record, setRecord] = useState<CallRecordView | null>(null)
  const [failure, setFailure] = useState<ApiFailure | null>(null)
  const [filter, setFilter] = useState<CallFilter>(NO_FILTER)
  const [reload, setReload] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = callId
      ? api(`/api/analytics/calls/${encodeURIComponent(callId)}`).then((r) => { if (!cancelled) setRecord(r as CallRecordView) })
      : Promise.all([api('/api/analytics/calls?limit=500'), api('/api/analytics/summary')]).then(([list, s]) => {
          if (cancelled) return
          setCalls(((list as { calls?: CallSummary[] } | null)?.calls) ?? [])
          setSummary(s as AnalyticsSummary)
        })
    load.then(() => { if (!cancelled) setFailure(null) }).catch((err) => { if (!cancelled) setFailure(err instanceof ApiError ? err.failure : 'unreachable') })
    return () => {
      cancelled = true
    }
  }, [callId, reload])

  const remove = useMemo(() => async (id: string) => {
    try {
      await api(`/api/analytics/calls/${encodeURIComponent(id)}`, { method: 'DELETE' })
      setNotice(`Deleted the record of ${callCode(id)}.`)
      setRecord(null)
      location.hash = '#/analytics/calls'
    } catch (err) {
      setFailure(err instanceof ApiError ? err.failure : 'unreachable')
    }
  }, [])

  if (failure === 'unauthorized') return <AccessForm onSaved={() => setReload((r) => r + 1)} />
  if (failure === 'missing' && callId) {
    return <Card><Empty title="This call is not stored">It may have been deleted, or nobody said yes to remembering it.</Empty><p className="text-center"><a className="btn-quiet" href="#/analytics/calls">All stored calls</a></p></Card>
  }
  if (failure) return <ErrorNote title={failureText(failure)} onRetry={() => setReload((r) => r + 1)} />

  if (callId) {
    return record && record.callId === callId ? <CallDetail record={record} onDelete={() => void remove(callId)} /> : <Skeleton className="h-64 w-full" />
  }
  return (
    <div className="space-y-4">
      {notice && <p role="status" className="rounded-xl bg-ok-soft px-4 py-3 font-semibold text-ok">{notice}</p>}
      {summary ? <KpiStrip summary={summary} /> : <Skeleton className="h-24 w-full" />}
      {calls ? <CallsTable calls={calls} filter={filter} onFilter={setFilter} /> : <Skeleton className="h-64 w-full" />}
    </div>
  )
}
