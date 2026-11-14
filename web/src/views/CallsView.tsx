import { useEffect, useMemo, useState } from 'react'
import CallBoard from '../live/CallBoard'
import {
  callCode,
  CLEANUP_FAILED,
  CONSENT_LABEL,
  filterCalls,
  isAnonymous,
  keptNote,
  keyPoints,
  languagesOf,
  NO_FILTER,
  keptItems,
  MINIMAL_NOTE,
  plainList,
  OUTCOME_LABEL,
  outcomeLabel,
  TERMS_ONLY_LABEL,
  termsOnlyDeal,
  toCallView,
  watchCalls,
  type AnalyticsSummary,
  type CallFilter,
  type CallRecordView,
  type CallSummary,
  type Consent,
  type KeyPointTone,
  type Outcome,
} from '../lib/analytics'
import { accessCode, api, ApiError, setAccessCode, type ApiFailure } from '../lib/api'
import { dealPrice, duration, languageLabel, millis } from '../lib/format'
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

/** Marks a call that is not linked to any farmer: no caller key was kept. */
function AnonymousChip() {
  return <span className="chip bg-sunk text-muted" title="No caller key was kept, so this call is not linked to any farmer.">Anonymous</span>
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
  if (!deal) return ''
  const crop = [deal.variety, deal.commodity].filter(Boolean).join(' ')
  const parts = [crop, deal.quantity, deal.price !== null ? dealPrice(deal.price, deal.unit, '/') : null, deal.market]
  return parts.filter(Boolean).join(' · ')
}

const OUTCOME_CLASS: Record<string, string> = {
  agreed: 'bg-ok-soft text-ok',
  no_deal: 'bg-warn-soft text-warn',
  info_only: 'bg-buyer-soft text-buyer',
  no_terms: 'bg-sunk text-muted',
}

function OutcomeChip({ outcome }: { outcome: string }) {
  return <span className={`chip ${OUTCOME_CLASS[outcome] ?? OUTCOME_CLASS.no_terms}`}>{outcomeLabel(outcome)}</span>
}

/** What else is worth seeing in a row: the connected buyer and any private caution. */
function rowExtras(c: CallSummary): string[] {
  const extras: string[] = []
  if (c.buyerName) extras.push(`Buyer: ${c.buyerName}`)
  if (c.cautions) extras.push(c.cautions === 1 ? '1 caution' : `${c.cautions} cautions`)
  return extras
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

/** The figures strip. Every number comes from the stored records, which cover every finished call. */
export function KpiStrip({ summary }: { summary: AnalyticsSummary }) {
  const pairs = Object.entries(summary.languagePairs).sort((a, b) => b[1] - a[1])
  const tools = Object.entries(summary.toolCounts).sort((a, b) => b[1] - a[1])
  const approximate = summary.approximate === true
  const split = summary.callsWithYes !== undefined && summary.callsWithoutYes !== undefined ? `${summary.callsWithYes} with a yes · ${summary.callsWithoutYes} without` : null
  const median = approximate ? 'approximate median' : 'median'
  const tiles = [
    { label: 'Finished calls', value: String(summary.storedCalls), note: split },
    { label: 'Both said yes to terms', value: String(summary.bothYesDeals), note: null },
    { label: 'Average length', value: summary.storedCalls ? duration(summary.averageDurationMs) : '—', note: null },
    { label: `Median reply time (measured${approximate ? ', approximate' : ''})`, value: summary.medianMs.reply !== undefined ? millis(summary.medianMs.reply) : '—', note: null },
  ]
  return (
    <section aria-label="Figures from stored calls" className="min-w-0 space-y-3">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles.map((tile) => (
          <div key={tile.label} className="card min-w-0 p-4">
            <dt className="text-sm font-semibold break-words text-muted">{tile.label}</dt>
            <dd className="mt-1 font-mono text-2xl font-bold tabular-nums">{tile.value}</dd>
            {tile.note && <dd className="mt-0.5 text-sm text-muted tabular-nums">{tile.note}</dd>}
          </div>
        ))}
      </dl>
      {(pairs.length > 0 || tools.length > 0 || summary.medianMs.transcript !== undefined) && (
        <div className="flex min-w-0 flex-wrap gap-x-6 gap-y-2 text-sm break-words text-muted">
          {summary.medianMs.transcript !== undefined && <p>Speech to text, {median}: <span className="font-semibold text-ink tabular-nums">{millis(summary.medianMs.transcript)}</span></p>}
          {summary.medianMs.translation !== undefined && <p>Translation, {median}: <span className="font-semibold text-ink tabular-nums">{millis(summary.medianMs.translation)}</span></p>}
          {pairs.length > 0 && <p>Languages: {pairs.map(([pair, n]) => <span key={pair} className="font-semibold text-ink">{pair.split(' ⇄ ').map((l) => (l === 'unknown' ? 'Not stored' : languageLabel(l))).join(' ⇄ ')} ({n}) </span>)}</p>}
          {tools.length > 0 && <p>Tools: {tools.map(([tool, n]) => <span key={tool} className="font-mono font-semibold break-all text-ink">{tool} ({n}) </span>)}</p>}
        </div>
      )}
      <p className="text-xs text-muted">
        Figures cover the calls stored now. Every finished call is stored, up to the newest 500; calls deleted or forgotten are not counted.
        {' '}When available, a call keeps its times, languages, tool names, private-advice kinds, latency medians and deal terms, whether or not anyone said yes. Per-turn times are kept only for a party that said yes, and the farmer’s name and memory only with the farmer’s yes. No call keeps transcripts, subtitles, translations or audio.
        {approximate && ' Medians across calls are approximate: each call keeps only its own medians.'}
        {(summary.unsavedCalls ?? 0) > 0 && ` ${summary.unsavedCalls} finished ${summary.unsavedCalls === 1 ? 'call is' : 'calls are'} waiting to be saved and ${summary.unsavedCalls === 1 ? 'is' : 'are'} not counted yet.`}
      </p>
    </section>
  )
}

/** Every finished call, with filters and sort. A call nobody said yes to says so in its row. */
export function CallsTable({ calls, filter, onFilter }: { calls: CallSummary[]; filter: CallFilter; onFilter: (f: CallFilter) => void }) {
  if (calls.length === 0) {
    return <Card><Empty title="No finished calls yet">Every finished call is listed here, including calls where nobody said yes. Calls appear once they end.</Empty></Card>
  }
  const shown = filterCalls(calls, filter)
  const languages = languagesOf(calls)
  return (
    <section aria-label="Calls" className="card overflow-hidden">
      <div className="flex flex-wrap items-end gap-3 border-b border-line p-3">
        <label className="min-w-0 flex-1 basis-40 text-sm font-semibold">Language
          <select className="field mt-1" value={filter.language} onChange={(e) => onFilter({ ...filter, language: e.target.value })}>
            <option value="all">All languages</option>
            {languages.map((l) => <option key={l} value={l}>{languageLabel(l)}</option>)}
          </select>
        </label>
        <label className="min-w-0 flex-1 basis-40 text-sm font-semibold">Outcome
          <select className="field mt-1" value={filter.outcome} onChange={(e) => onFilter({ ...filter, outcome: e.target.value as Outcome | 'all' })}>
            <option value="all">All outcomes</option>
            {(Object.keys(OUTCOME_LABEL) as Outcome[]).map((o) => <option key={o} value={o}>{OUTCOME_LABEL[o]}</option>)}
          </select>
        </label>
        <label className="min-w-0 flex-1 basis-40 text-sm font-semibold">Sort
          <select className="field mt-1" value={filter.sort} onChange={(e) => onFilter({ ...filter, sort: e.target.value as CallFilter['sort'] })}>
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
            <option value="longest">Longest first</option>
          </select>
        </label>
      </div>
      {shown.length === 0 ? (
        <Empty title="No calls match">Change the language or outcome filter.</Empty>
      ) : (
        <ul className="divide-y divide-line">
          {shown.map((c) => {
            const terms = dealLine(c.deal)
            const extras = rowExtras(c)
            const note = keptNote(c)
            return (
              <li key={c.callId}>
                <a href={`#/analytics/calls?id=${encodeURIComponent(c.callId)}`} className="grid gap-x-4 gap-y-1.5 px-4 py-3 transition-colors hover:bg-sunk md:grid-cols-[8.5rem_4rem_minmax(0,0.9fr)_minmax(0,1.6fr)_auto] md:items-center">
                  <span className="flex items-baseline gap-2 md:block">
                    <span className="font-mono font-bold" title={c.callId}>{callCode(c.callId)}</span>
                    <span className="text-sm text-muted md:block">{dateTime(c.startedMs)}</span>
                  </span>
                  <span className="font-mono text-sm tabular-nums"><span className="sr-only">Length </span>{duration(c.durationMs)}</span>
                  <span className="min-w-0 truncate text-sm">{pairLabel(c.languages)}</span>
                  <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                    <OutcomeChip outcome={c.outcome} />
                    {terms && <span className="min-w-0 font-semibold md:truncate" title={terms}>{terms}</span>}
                    {extras.length > 0 && <span className="text-muted">{extras.join(' · ')}</span>}
                    {termsOnlyDeal(c, c.deal) && <span className="chip bg-sunk text-muted">{TERMS_ONLY_LABEL}</span>}
                    {note && <span className="basis-full text-muted">{note}</span>}
                  </span>
                  <span className="flex flex-wrap gap-1.5">
                    <ConsentChip who="Farmer" consent={c.consentFarmer} />
                    {isAnonymous(c) && <AnonymousChip />}
                    <ConsentChip who="Buyer" consent={c.consentBuyer} />
                  </span>
                </a>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

const TONE_CLASS: Record<KeyPointTone, string> = { ok: 'text-ok', bad: 'text-bad', warn: 'text-warn', plain: 'text-faint' }

/** In the place the live subtitles take: the call at a glance, from the stored details only. */
export function KeyPointsCard({ record }: { record: CallRecordView }) {
  const points = keyPoints(record)
  return (
    <section aria-labelledby="key-points" className="card p-4 md:p-5">
      <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 id="key-points" className="text-lg font-bold">Key points</h2>
        <span className="chip bg-sunk text-muted">From the stored details</span>
      </header>
      <ul className="space-y-2">
        {points.map((p) => (
          <li key={p.id} className="flex items-start gap-2.5">
            <span aria-hidden="true" className={`mt-2 h-2 w-2 shrink-0 rounded-full bg-current ${TONE_CLASS[p.tone]}`} />
            <span>{p.text}</span>
          </li>
        ))}
      </ul>
      <p className="mt-4 border-t border-line pt-3 text-sm text-muted">The conversation itself is never stored: no subtitles, no recordings and no private advice text. These points are worked out from the stored details, without asking a model.</p>
    </section>
  )
}

/** One stored call, on the same cards as the live call, without the conversation. */
export function CallDetail({ record, onDelete, receipt = false }: { record: CallRecordView; onDelete?: () => void; receipt?: boolean }) {
  const call = toCallView(record, { receipt })
  const [confirm, setConfirm] = useState(false)
  const [copied, setCopied] = useState(false)
  const older = (record.schemaVersion ?? 1) < 2
  const note = keptNote(record)
  const items = keptItems(record)
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <a href="#/analytics/calls" className="btn-quiet btn-sm"><ArrowIcon className="rotate-180" /> All calls</a>
        <h2 className="text-xl font-bold"><span className="font-mono">{callCode(record.callId)}</span></h2>
        <button type="button" className="min-w-0 font-mono text-sm break-all text-faint underline-offset-4 hover:underline" title="Copy the full call id" onClick={() => { void navigator.clipboard?.writeText(record.callId).then(() => setCopied(true)) }}>
          {record.callId}{copied ? ' (copied)' : ''}
        </button>
        {onDelete && <button type="button" className="btn-danger btn-sm ml-auto" onClick={() => setConfirm(true)}><TrashIcon /> Delete record</button>}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <ConsentChip who="Farmer" consent={record.consentFarmer} />
        {isAnonymous(record) && <AnonymousChip />}
        <ConsentChip who="Buyer" consent={record.consentBuyer} />
        <OutcomeChip outcome={record.outcome} />
        {older && <span className="text-sm text-muted">Stored before leg end times, language switches and confirmation times were kept.</span>}
      </div>
      {note && (
        <section aria-label="What was not kept" className="rounded-xl bg-sunk px-4 py-3 text-sm">
          <p className="font-semibold">{note}.</p>
          {note === MINIMAL_NOTE ? (
            <p className="mt-0.5 text-muted">Its other details could not be built or read safely, so they were left out.</p>
          ) : (
            <p className="mt-0.5 text-muted">Kept in this record: {plainList(items)}. Not kept: per-turn times, the caller key, and the farmer’s name and memory. No call keeps transcripts, subtitles, translations or audio.</p>
          )}
        </section>
      )}
      <CallBoard call={call} main={<KeyPointsCard record={record} />} />
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
  return failure === 'unreachable' ? 'The backend did not answer. Start it and try again.' : 'The calls could not be loaded.'
}

/** The backend answered 503: its one-time privacy cleanup of older records is running, or did not finish. */
export function NotReadyNote({ code, onRetry }: { code: string | null; onRetry: () => void }) {
  const failed = code === CLEANUP_FAILED
  return (
    <div role="status" className="flex flex-wrap items-start gap-3 rounded-xl border border-line bg-sunk p-4">
      <div className="min-w-0 flex-1 basis-56">
        <p className="font-bold">{failed ? 'The calls are closed for now' : 'The calls will be back shortly'}</p>
        <p className="mt-0.5 text-muted">
          {failed
            ? 'A one-time privacy cleanup of older records did not finish, so no calls are shown. Check the backend log, then try again.'
            : 'A one-time privacy cleanup of older records is running. This page tries again on its own every few seconds.'}
        </p>
      </div>
      <button type="button" className="btn-quiet" onClick={onRetry}>Try again</button>
    </div>
  )
}

/** The Calls tab: figures, the stored calls, or one call when `callId` is set. */
export default function CallsView({ callId }: { callId: string | null }) {
  const [calls, setCalls] = useState<CallSummary[] | null>(null)
  const [summary, setSummary] = useState<AnalyticsSummary | null>(null)
  const [record, setRecord] = useState<CallRecordView | null>(null)
  const [failure, setFailure] = useState<ApiFailure | null>(null)
  const [failureCode, setFailureCode] = useState<string | null>(null)
  const [filter, setFilter] = useState<CallFilter>(NO_FILTER)
  const [reload, setReload] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const [receipt, setReceipt] = useState<{ callId: string; found: boolean } | null>(null)

  useEffect(
    () =>
      watchCalls(callId, (load) => {
        if (load.record) setRecord(load.record)
        if (load.calls) setCalls(load.calls)
        if (load.summary) setSummary(load.summary)
        setFailure(load.failure)
        setFailureCode(load.failureCode)
      }),
    [callId, reload],
  )

  // The receipt lives in the backend's memory, so it is gone after a restart: link to it only when it is still there.
  const hasYes = Boolean(record?.data.deal && (record.data.deal.state === 'agreed' || record.data.deal.farmerYes || record.data.deal.buyerYes))
  const recordId = record?.callId ?? null
  useEffect(() => {
    if (!recordId || !hasYes) return
    let cancelled = false
    api(`/api/deals/${encodeURIComponent(recordId)}/receipt`)
      .then(() => { if (!cancelled) setReceipt({ callId: recordId, found: true }) })
      .catch(() => { if (!cancelled) setReceipt({ callId: recordId, found: false }) })
    return () => {
      cancelled = true
    }
  }, [recordId, hasYes])

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
    return <Card><Empty title="This call is not stored">Its record may have been deleted, removed when its farmer asked to be forgotten or to make room for newer calls, or set aside because it could not be read safely.</Empty><p className="text-center"><a className="btn-quiet" href="#/analytics/calls">All calls</a></p></Card>
  }
  if (failure === 'unavailable') return <NotReadyNote code={failureCode} onRetry={() => setReload((r) => r + 1)} />
  if (failure) return <ErrorNote title={failureText(failure)} onRetry={() => setReload((r) => r + 1)} />

  if (callId) {
    return record && record.callId === callId ? <CallDetail record={record} receipt={receipt?.callId === callId && receipt.found} onDelete={() => void remove(callId)} /> : <Skeleton className="h-64 w-full" />
  }
  return (
    <div className="space-y-4">
      {notice && <p role="status" className="rounded-xl bg-ok-soft px-4 py-3 font-semibold text-ok">{notice}</p>}
      {summary ? <KpiStrip summary={summary} /> : <Skeleton className="h-24 w-full" />}
      {calls ? <CallsTable calls={calls} filter={filter} onFilter={setFilter} /> : <Skeleton className="h-64 w-full" />}
    </div>
  )
}
