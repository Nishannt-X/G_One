import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { accessCode, api, ApiError, setAccessCode, type ApiFailure } from '../lib/api'
import { cropSummary, forgetSeen, fromRecord, maskedKey, openFollowUps, parseReceipt, placeOf, readSeen, searchFarmers, toFarmer, type Farmer, type FarmerDeal, type FarmerValue } from '../lib/farmers'
import { callRows, callWhen, closeTop, farmersHash, focusReturnSelector, overlayStack, parseFarmersHash, shownLocation, type CallRow, type FarmersLocation } from '../lib/farmersOverlay'
import { completeness, groupedProfile } from '../lib/profileVariables'
import { useHash } from '../lib/route'
import { englishText, initialOf } from '../lib/farmersScript'
import { dealPrice, duration, languageLabel, shortDate } from '../lib/format'
import { forgetCaller, forgetNotice, parseMemory } from '../lib/memory'
import type { Origin } from '../types/console'
import { AlertIcon, ArrowIcon, CheckIcon, ChevronIcon, CrossIcon, LockIcon, PhoneIcon, PlayIcon, SearchIcon, TrashIcon, WheatIcon } from '../ui/icons'
import { Dialog, Empty, ErrorNote, Overlay, Skeleton } from '../ui/parts'

const NOT_TOLD = 'Not told yet'

function Provenance({ origin }: { origin: Origin }) {
  return <span className={`badge ${origin === 'said' ? 'badge-ok' : ''}`}>{origin === 'said' ? 'Told' : 'Inferred'}</span>
}

function OriginBadge({ farmer }: { farmer: Farmer }) {
  if (farmer.origin === 'memory') return null
  return <span className="badge badge-warn">{farmer.origin === 'sample' ? 'Sample call' : 'Synthetic'}</span>
}

function Initial({ farmer, large = false }: { farmer: Farmer; large?: boolean }) {
  const letter = initialOf(farmer.name)
  return (
    <span aria-hidden="true" className={`grid shrink-0 place-items-center rounded-full bg-farmer-soft font-semibold text-farmer ${large ? 'h-14 w-14 text-2xl' : 'h-11 w-11 text-xl'}`}>
      {letter ?? <WheatIcon />}
    </span>
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** One farmer in the list. `deals` is undefined while their deals are still being looked up. */
export function FarmerRow({ farmer, seed = false, onOpen }: { farmer: Farmer; seed?: boolean; onOpen?: (key: string) => void }) {
  const { shown, more } = cropSummary(farmer.crops)
  const followUps = openFollowUps(farmer.gists).length
  const deals = farmer.deals?.length
  const place = placeOf(farmer)
  return (
    <li>
      <a
        href={farmersHash({ key: farmer.callerKey, callId: null, seed })}
        data-farmer-row={farmer.callerKey}
        onClick={(e) => {
          // A plain click opens the overlay in place; a modified click keeps the browser's own behaviour.
          if (!onOpen || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
          e.preventDefault()
          onOpen(farmer.callerKey)
        }}
        className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1.5 rounded-xl px-3 py-3 transition-colors hover:bg-sunk lg:grid-cols-[auto_minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1.3fr)_5.5rem_3.5rem_5.5rem_auto]">
        <Initial farmer={farmer} />
        <div className="min-w-0">
          <p className="flex min-w-0 items-center gap-2">
            <span className="truncate text-lg leading-tight font-semibold">{farmer.name ?? 'Name not given'}</span>
            <OriginBadge farmer={farmer} />
          </p>
          <p className="truncate text-sm text-muted">{place ?? maskedKey(farmer.callerKey)}<span className="lg:hidden"> · {languageLabel(farmer.language)}</span></p>
        </div>
        <p className="hidden truncate text-muted lg:block">{languageLabel(farmer.language)}</p>
        <div className="col-start-2 row-start-2 flex min-w-0 flex-wrap items-center gap-1 lg:col-auto lg:row-auto">
          {shown.length > 0 ? shown.map((c) => <span key={c} className="badge capitalize">{c}</span>) : <span className="text-sm text-faint">No crops yet</span>}
          {more > 0 && <span className="badge" aria-label={`${more} more crops`}>+{more}</span>}
        </div>
        <p className="col-start-2 row-start-3 text-sm text-muted tabular-nums lg:col-auto lg:row-auto lg:text-base">
          <span className="lg:hidden">Last call </span>{shortDate(farmer.lastCallMs)}
          <span className="lg:hidden"> · {deals === undefined ? 'deals…' : plural(deals, 'deal', 'deals')} · {plural(followUps, 'follow-up', 'follow-ups')}</span>
        </p>
        <p className="hidden text-muted tabular-nums lg:block" aria-label={deals === undefined ? 'Deals loading' : plural(deals, 'deal', 'deals')}>{deals ?? '…'}</p>
        <p className="hidden tabular-nums lg:block" aria-label={plural(followUps, 'open follow-up', 'open follow-ups')}>
          {followUps > 0 ? <span className="badge badge-brand">{followUps} open</span> : <span className="text-faint">None</span>}
        </p>
        <ChevronIcon className="col-start-3 row-start-1 -rotate-90 text-faint lg:col-auto lg:row-auto" />
      </a>
    </li>
  )
}

/** "Profile: 9 of 11 details known", with a thin bar. The total comes from the catalog, so new variables extend it. */
function Completeness({ farmer }: { farmer: Farmer }) {
  const { known, total } = completeness(farmer)
  const pct = total > 0 ? Math.round((known / total) * 100) : 0
  return (
    <div className="flex items-center gap-3">
      <p className="shrink-0 text-sm font-semibold">Profile completeness <span className="text-muted tabular-nums">{known} of {total} known</span></p>
      <span aria-hidden="true" className="h-1.5 min-w-16 flex-1 overflow-hidden rounded-full bg-sunk">
        <span className="block h-full rounded-full bg-brand" style={{ width: `${pct}%` }} />
      </span>
    </div>
  )
}

function VariableRow({ label, value }: { label: string; value: FarmerValue | undefined }) {
  return (
    <div className="py-1.5">
      <div className="flex items-center justify-between gap-3">
        <dt className="text-sm text-muted">{label}</dt>
        {value && <Provenance origin={value.source} />}
      </div>
      {value ? (
        <dd className="flex flex-wrap items-baseline justify-between gap-x-3">
          <span className="min-w-0 font-semibold break-words">{value.value}</span>
          {value.atMs ? <span className="text-xs whitespace-nowrap text-faint">Last confirmed {shortDate(value.atMs)}</span> : null}
        </dd>
      ) : (
        <dd className="text-faint italic">{NOT_TOLD}</dd>
      )}
    </div>
  )
}

/** What G-One has learned about the farmer so far, grouped from the profile catalog. */
export function FarmerProfile({ farmer }: { farmer: Farmer }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {groupedProfile(farmer).map(({ group, rows }) => (
        <section key={group} aria-label={group} className={`tile px-4 py-3 ${group === 'Identity' ? 'sm:row-span-3' : ''}`}>
          <h3 className="text-sm font-bold tracking-wide text-muted uppercase">{group}</h3>
          <dl className="mt-1 divide-y divide-line">
            {rows.map(({ variable, value }) => <VariableRow key={variable.key} label={variable.label} value={value} />)}
          </dl>
        </section>
      ))}
    </div>
  )
}

function OutcomeChip({ outcome }: { outcome: CallRow['outcome'] }) {
  return <span className={`badge ${outcome.tone === 'ok' ? 'badge-ok' : outcome.tone === 'warn' ? 'badge-warn' : ''}`}>{outcome.label}</span>
}

/** The farmer's calls, one basic line each; a row opens that call's overlay. */
export function FarmerCalls({ rows, onOpen }: { rows: CallRow[]; onOpen: (callId: string) => void }) {
  if (rows.length === 0) return <p className="text-faint italic">No calls saved yet.</p>
  return (
    <ol className="-mx-2 space-y-0.5">
      {rows.map((r) => (
        <li key={r.callId}>
          <button type="button" data-call-row={r.callId} onClick={() => onOpen(r.callId)} className="flex w-full cursor-pointer items-center gap-3 rounded-xl px-2 py-2.5 text-left transition-colors hover:bg-sunk">
            <span className="min-w-0 flex-1">
              <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="font-semibold tabular-nums">{callWhen(r.startedMs)}</span>
                {r.lengthMs !== null && <span className="text-sm text-muted tabular-nums" aria-label={`Length ${duration(r.lengthMs)}`}>{duration(r.lengthMs)}</span>}
                <span className="text-sm text-muted">{languageLabel(r.language)}</span>
                <OutcomeChip outcome={r.outcome} />
              </span>
              <span className="mt-0.5 block truncate text-sm text-muted">
                {[r.dealLine, plural(r.followUps.length, 'follow-up', 'follow-ups')].filter(Boolean).join(' · ')}
              </span>
            </span>
            <ChevronIcon className="shrink-0 -rotate-90 text-faint" />
          </button>
        </li>
      ))}
    </ol>
  )
}

/** One farmer's overlay: header, inferred profile, their calls, and the forget footer. */
export function FarmerDetail({ farmer, deals, onClose, onForget, onOpenCall }: { farmer: Farmer; deals: FarmerDeal[] | undefined; onClose: () => void; onForget: () => void; onOpenCall: (callId: string) => void }) {
  const rows = callRows(farmer, deals)
  return (
    <>
      <header className="flex shrink-0 items-start gap-3 border-b border-line p-4 sm:p-5">
        <Initial farmer={farmer} large />
        <div className="min-w-0 flex-1">
          <h2 id="farmer-overlay-title" className="truncate text-2xl leading-tight font-semibold">{farmer.name ?? 'Name not given'}</h2>
          <p className="truncate text-sm text-muted">{maskedKey(farmer.callerKey)} · {languageLabel(farmer.language)}</p>
          <p className="mt-1 flex flex-wrap gap-1"><OriginBadge farmer={farmer} /><span className="badge">{plural(farmer.callCount, 'call', 'calls')}</span></p>
        </div>
        <button type="button" className="btn-ghost w-11 px-0" aria-label="Close" onClick={onClose} data-autofocus><CrossIcon /></button>
      </header>

      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4 sm:p-5">
        <section aria-label="Profile" className="space-y-3">
          <Completeness farmer={farmer} />
          <FarmerProfile farmer={farmer} />
        </section>
        <section aria-labelledby="farmer-calls-title">
          <h3 id="farmer-calls-title" className="text-lg leading-tight font-semibold">Calls</h3>
          <p className="mb-2 text-sm text-faint">Open a call for its basics and G-One's short note.</p>
          <FarmerCalls rows={rows} onOpen={onOpenCall} />
        </section>
      </div>

      <footer className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-line px-4 py-3 sm:px-5">
        <p className="hidden text-sm text-faint sm:block">Forgetting removes their facts and call summaries from G-One's memory.</p>
        <button type="button" className="btn-quiet btn-sm text-bad" onClick={onForget}><TrashIcon /> Forget this farmer</button>
      </footer>
    </>
  )
}

const sentence = (v: string) => (v ? v[0].toUpperCase() + v.slice(1) : v)

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="font-semibold break-words">{children}</dd>
    </div>
  )
}

/** One call's overlay: only the basics, the deal if there was one, its follow-ups and the short note. */
export function CallDetail({ farmer, row, checking = false, onClose }: { farmer: Farmer; row: CallRow; checking?: boolean; onClose: () => void }) {
  const deal = row.deal
  const where = deal ? deal.buyer ?? (deal.market ? englishText(deal.market) ?? deal.market : null) : null
  return (
    <>
      <header className="flex shrink-0 items-start gap-3 border-b border-line p-4 sm:p-5">
        <span aria-hidden="true" className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-sunk text-muted"><PhoneIcon /></span>
        <div className="min-w-0 flex-1">
          <h2 id="call-overlay-title" className="text-xl leading-tight font-semibold">Call on {callWhen(row.startedMs)}</h2>
          <p className="truncate text-sm text-muted">{farmer.name ?? 'Name not given'} · {maskedKey(farmer.callerKey)}</p>
          {farmer.origin !== 'memory' && <p className="mt-1"><OriginBadge farmer={farmer} /></p>}
        </div>
        <button type="button" className="btn-ghost w-11 px-0" aria-label="Close call" onClick={onClose} data-autofocus><CrossIcon /></button>
      </header>

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4 sm:p-5">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Fact label="When"><span className="tabular-nums">{callWhen(row.startedMs)}</span></Fact>
          <Fact label="Length"><span className="tabular-nums">{row.lengthMs !== null ? duration(row.lengthMs) : 'Not recorded'}</span></Fact>
          <Fact label="Language">{languageLabel(row.language)}</Fact>
          <div className="min-w-0"><dt className="text-sm text-muted">Outcome</dt><dd className="mt-0.5"><OutcomeChip outcome={row.outcome} /></dd></div>
        </dl>

        <section aria-label="Deal" className="tile p-4">
          <h3 className="font-semibold">Deal</h3>
          {checking ? (
            <Skeleton className="mt-2 h-10 w-full" />
          ) : deal ? (
            <dl className="mt-2 grid grid-cols-2 gap-3">
              <Fact label="Crop">{sentence(englishText(deal.commodity) ?? deal.commodity)}</Fact>
              <Fact label="Quantity">{deal.quantity || 'Not stated'}</Fact>
              <Fact label="Price">{deal.price !== null ? dealPrice(deal.price, deal.unit || null, ' per ') : 'Not stated'}</Fact>
              <Fact label={deal.buyer ? 'Buyer' : 'Market'}>{where ?? 'Not stated'}</Fact>
              {deal.pickup && <Fact label="Pickup">{englishText(deal.pickup) ?? deal.pickup}</Fact>}
            </dl>
          ) : (
            <p className="mt-1 text-faint italic">No deal was recorded on this call.</p>
          )}
        </section>

        <section aria-label="Follow-ups from this call">
          <h3 className="font-semibold">Follow-ups from this call</h3>
          {row.followUps.length === 0 ? (
            <p className="mt-1 text-faint italic">None.</p>
          ) : (
            <ul className="mt-1.5 space-y-1.5">
              {row.followUps.map((f) => <li key={f} className="flex items-start gap-2"><span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand" />{f}</li>)}
            </ul>
          )}
        </section>

        <section aria-label="G-One's note">
          <h3 className="flex flex-wrap items-baseline gap-x-2 font-semibold">G-One's note <span className="text-sm font-normal text-faint">short note, not a transcript</span></h3>
          <p className="mt-1 text-muted">{row.note ?? 'No note was saved.'}</p>
        </section>
      </div>

      <footer className="shrink-0 border-t border-line px-4 py-3 sm:px-5">
        <a className="link inline-flex items-center gap-1.5" href={`#/analytics/calls?id=${encodeURIComponent(row.callId)}`}>See this call in Analytics <ArrowIcon /></a>
      </footer>
    </>
  )
}

/** The deals made on a memory farmer's newest calls, from their receipts. */
async function loadDeals(farmer: Farmer): Promise<FarmerDeal[]> {
  const calls = farmer.gists.slice(0, 6).map((g) => g.callId)
  const rows = await Promise.all(calls.map((id) => api(`/api/deals/${encodeURIComponent(id)}/receipt`).then((body) => parseReceipt(body, id)).catch(() => null)))
  return rows.filter((d): d is FarmerDeal => d !== null)
}

function CallOverlay({ farmer, callId, onClose }: { farmer: Farmer; callId: string; onClose: () => void }) {
  const row = callRows(farmer, farmer.deals).find((r) => r.callId === callId)
  return row ? <CallDetail farmer={farmer} row={row} checking={farmer.deals === undefined} onClose={onClose} /> : null
}

function AccessForm({ onSaved }: { onSaved: () => void }) {
  const [code, setCode] = useState(accessCode())
  return (
    <form className="card flex flex-wrap items-center gap-2 p-4" onSubmit={(e) => { e.preventDefault(); setAccessCode(code); onSaved() }}>
      <span className="flex items-center gap-2 font-semibold"><LockIcon /> This memory needs an access code</span>
      <label className="min-w-40 flex-1"><span className="sr-only">Access code</span><input type="password" className="field" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" /></label>
      <button type="submit" className="btn-primary" disabled={!code}>Unlock</button>
    </form>
  )
}

/** `openKey` is also read from the hash here, together with the call, so the two always agree. */
export default function FarmersPage({ seed }: { openKey: string | null; seed: boolean }) {
  const showSeed = import.meta.env.DEV && seed
  const [farmers, setFarmers] = useState<Farmer[] | null>(null)
  const [failure, setFailure] = useState<ApiFailure | null>(null)
  const [query, setQuery] = useState('')
  const [reload, setReload] = useState(0)
  const [forgetting, setForgetting] = useState<Farmer | null>(null)
  const [busy, setBusy] = useState(false)
  const [forgetFailed, setForgetFailed] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const seen = readSeen()
      let problem: ApiFailure | null = null
      const rows = await Promise.all(
        seen.map(async (entry): Promise<Farmer | null> => {
          if (entry.origin !== 'memory') return entry.snapshot ? toFarmer(entry.callerKey, entry.origin, entry.language, entry.seenMs, entry.snapshot.facts, entry.snapshot.gists) : null
          try {
            const record = parseMemory(await api(`/api/memory/${encodeURIComponent(entry.callerKey)}`), entry.callerKey)
            return record.known ? fromRecord(entry, record) : null
          } catch (error) {
            problem = error instanceof ApiError ? error.failure : 'server'
            return null
          }
        }),
      )
      if (cancelled) return
      const seeded = import.meta.env.DEV && showSeed ? (await import('../dev/seedFarmers')).seedFarmers(Date.now()) : []
      if (cancelled) return
      setFailure(problem)
      const list = [...seeded, ...rows.filter((f): f is Farmer => f !== null)].sort((a, b) => b.lastCallMs - a.lastCallMs)
      // A sample caller is not in the store and has no receipts; a memory farmer's deals come from their receipts.
      setFarmers(list.map((f) => (f.origin === 'memory' || f.deals ? f : { ...f, deals: [] })))
      const withDeals = await Promise.all(list.map(async (f) => (f.origin === 'memory' && !f.deals ? { ...f, deals: await loadDeals(f) } : f)))
      if (!cancelled) setFarmers((current) => (current ? withDeals.filter((f) => current.some((c) => c.callerKey === f.callerKey)) : current))
    })()
    return () => {
      cancelled = true
    }
  }, [showSeed, reload])

  const shown = useMemo(() => searchFarmers(farmers ?? [], query), [farmers, query])
  const hash = useHash()
  // One source for the farmer and the call, so a render never pairs one farmer with another farmer's call.
  const at: FarmersLocation = useMemo(() => parseFarmersHash(hash), [hash])
  const stack = overlayStack(farmers, at)
  const [farmerLayer, callLayer] = stack
  const shownAt = shownLocation(at, stack)
  const open = farmerLayer?.farmer ?? null

  /** Opens an overlay as a new history entry, so Back closes it again. */
  const go = useCallback((next: FarmersLocation) => {
    location.hash = farmersHash(next)
    history.replaceState({ ...(history.state as object | null), farmersOverlay: true }, '')
  }, [])
  /** Closes the top overlay: back to the entry it was opened from, or (after a deep link) replaces this entry. */
  const closeTopOverlay = useCallback(() => {
    if (!location.hash.startsWith('#/farmers/')) return
    if ((history.state as { farmersOverlay?: boolean } | null)?.farmersOverlay) history.back()
    else location.replace(farmersHash(closeTop(shownAt)))
  }, [shownAt])
  const closeDetail = useCallback(() => {
    if (location.hash.startsWith('#/farmers/')) location.replace(farmersHash({ key: null, callId: null, seed: showSeed }))
  }, [showSeed])
  const farmerFocus = focusReturnSelector({ ...shownAt, callId: null })
  const callFocus = focusReturnSelector(shownAt)

  const forget = async () => {
    if (!forgetting) return
    const target = forgetting
    setBusy(true)
    setForgetFailed(false)
    try {
      const result = target.origin === 'memory' ? await forgetCaller(target.callerKey) : 'done'
      forgetSeen(target.callerKey)
      setFarmers((list) => list?.filter((f) => f.callerKey !== target.callerKey) ?? null)
      setForgetting(null)
      setNotice(forgetNotice(target.name ?? maskedKey(target.callerKey), result))
      closeDetail()
    } catch {
      setForgetFailed(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="shell py-8 md:py-12">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-2xl">
          <p className="kicker">Farmer memory</p>
          <h1 className="mt-2 text-headline font-semibold">Every farmer, picked up where they left off.</h1>
          <p className="mt-3 text-lg text-muted">Open a farmer to see what G-One has learned about them so far, and their calls. Each fact says whether the farmer told G-One or G-One inferred it. Memory sits against a hash of the number, and any farmer can be forgotten.</p>
        </div>
        {import.meta.env.DEV && !showSeed && <a className="btn-quiet btn-sm" href="#/farmers?seed=1">Load synthetic farmers (dev only)</a>}
      </header>

      {showSeed && <p role="note" className="mt-5 rounded-xl border border-dashed border-whisper-edge bg-whisper-soft px-4 py-2 font-semibold text-whisper">Showing invented farmers for layout work. Development builds only.</p>}
      {notice && <p role="status" className="animate-rise mt-5 flex items-center gap-2 rounded-xl bg-ok-soft px-4 py-3 font-semibold text-ok"><CheckIcon /> {notice}</p>}

      <div className="mt-6 space-y-4">
        {failure === 'unauthorized' ? (
          <AccessForm onSaved={() => setReload((r) => r + 1)} />
        ) : failure ? (
          <ErrorNote title="Some farmers could not be loaded" onRetry={() => setReload((r) => r + 1)}>The memory service did not answer. Farmers already on screen are up to date.</ErrorNote>
        ) : null}

        <section aria-label="Farmers" className="card overflow-hidden">
          <div className="flex items-center gap-3 border-b border-line p-3">
            <label className="relative min-w-0 flex-1">
              <span className="sr-only">Search farmers</span>
              <span className="pointer-events-none absolute inset-y-0 left-3 grid place-items-center text-muted"><SearchIcon /></span>
              <input className="field pl-10" placeholder="Search by name, crop, district or language" value={query} onChange={(e) => setQuery(e.target.value)} />
            </label>
            <p className="hidden shrink-0 text-muted tabular-nums sm:block">{farmers ? `${shown.length} of ${farmers.length}` : ' '}</p>
          </div>
          <div aria-hidden="true" className="hidden grid-cols-[2.75rem_minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1.3fr)_5.5rem_3.5rem_5.5rem_1.25rem] gap-x-3 border-b border-line px-6 py-2 text-sm font-semibold text-muted lg:grid">
            <span /><span>Farmer</span><span>Language</span><span>Crops</span><span>Last call</span><span>Deals</span><span>Follow-ups</span><span />
          </div>
          {farmers === null ? (
            <div className="space-y-3 p-4">
              <Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" />
            </div>
          ) : farmers.length === 0 ? (
            <Empty icon={<WheatIcon />} title="No farmers here yet" action={<div className="flex flex-wrap justify-center gap-2"><a className="btn-primary" href="#/demo?autoplay=1"><PlayIcon /> Play the sample call</a><a className="btn-quiet" href="#/call"><PhoneIcon /> Make a call</a></div>}>
              A farmer appears here after this browser has watched them call. Memory has no public list, so nothing shows until then.
            </Empty>
          ) : shown.length === 0 ? (
            <Empty icon={<SearchIcon />} title="No farmer matches that">Try a crop, a district or part of a name.</Empty>
          ) : (
            <ul className="divide-y divide-line p-1.5">
              {shown.map((f) => <FarmerRow key={f.callerKey} farmer={f} seed={showSeed} onOpen={(key) => go({ key, callId: null, seed: showSeed })} />)}
            </ul>
          )}
        </section>
      </div>

      <Overlay open={open !== null} onClose={closeTopOverlay} labelledBy="farmer-overlay-title" returnFocus={() => (farmerFocus ? document.querySelector<HTMLElement>(farmerFocus) : null)}>
        {open && <FarmerDetail farmer={open} deals={open.deals} onClose={closeTopOverlay} onForget={() => { setForgetFailed(false); setForgetting(open) }} onOpenCall={(callId) => go({ key: open.callerKey, callId, seed: showSeed })} />}
      </Overlay>
      <Overlay open={callLayer?.kind === 'call'} onClose={closeTopOverlay} labelledBy="call-overlay-title" returnFocus={() => (callFocus ? document.querySelector<HTMLElement>(callFocus) : null)}>
        {callLayer?.kind === 'call' && <CallOverlay farmer={callLayer.farmer} callId={callLayer.gist.callId} onClose={closeTopOverlay} />}
      </Overlay>
      <Dialog open={forgetting !== null} onClose={() => setForgetting(null)} title="Forget this farmer?">
        <div className="space-y-4">
          <p className="flex items-start gap-3">
            <AlertIcon className="mt-0.5 text-bad" />
            <span>G-One will lose <strong className="font-semibold">{forgetting?.name ?? 'this caller'}</strong>'s facts and {forgetting?.gists.length ?? 0} call {forgetting?.gists.length === 1 ? 'summary' : 'summaries'}. If they call again, they start from zero. This cannot be undone.</span>
          </p>
          {forgetFailed && <p role="alert" className="font-semibold text-bad">That did not work. The memory was not changed.</p>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn-quiet" disabled={busy} onClick={() => setForgetting(null)}>Keep them</button>
            <button type="button" className="btn-danger" disabled={busy} onClick={() => void forget()}>{busy ? 'Forgetting…' : 'Forget this farmer'}</button>
          </div>
        </div>
      </Dialog>
    </div>
  )
}
